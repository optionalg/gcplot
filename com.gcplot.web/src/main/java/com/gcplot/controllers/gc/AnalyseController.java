package com.gcplot.controllers.gc;

import com.gcplot.Identifier;
import com.gcplot.commons.ConfigProperty;
import com.gcplot.commons.ErrorMessages;
import com.gcplot.controllers.Controller;
import com.gcplot.messages.*;
import com.gcplot.model.VMVersion;
import com.gcplot.model.gc.*;
import com.gcplot.repository.GCAnalyseRepository;
import com.gcplot.repository.operations.analyse.*;
import com.gcplot.roles.Restrictions;
import com.gcplot.web.RequestContext;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.springframework.beans.factory.annotation.Autowired;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * @author <a href="mailto:art.dm.ser@gmail.com">Artem Dmitriev</a>
 *         8/13/16
 */
public class AnalyseController extends Controller {
    protected LoadingCache<Identifier, Long> newAnalyses;

    @PostConstruct
    public void init() {
        newAnalyses = Caffeine.newBuilder()
                .maximumSize(config.readLong(ConfigProperty.USER_ANALYSE_COUNT_CACHE_SIZE))
                .expireAfterWrite(config.readLong(ConfigProperty.USER_ANALYSE_COUNT_CACHE_SECONDS), TimeUnit.SECONDS)
                .build(k -> analyseRepository.analysesCount(k).orElse(0));

        dispatcher.requireAuth().filter(c -> c.hasParam("id"), "Param 'id' of analyse is missing.")
                .get("/analyse/get", this::analyse);
        dispatcher.requireAuth().filter(c -> c.hasParam("id"), "Param 'id' of analyse is missing.")
                .delete("/analyse/delete", this::deleteAnalyse);
        dispatcher.requireAuth().get("/analyse/all", this::analyses);
        dispatcher.requireAuth().post("/analyse/update", UpdateAnalyseRequest.class, this::updateAnalyse);
        dispatcher.requireAuth().post("/analyse/jvm/add", AddJvmRequest.class, this::addJvm);
        dispatcher.requireAuth().post("/analyse/jvm/update/version", UpdateJvmVersionRequest.class, this::updateJvmVersion);
        dispatcher.requireAuth().post("/analyse/jvm/update/info", UpdateJvmInfoRequest.class, this::updateJvmInfo);
        dispatcher.requireAuth().post("/analyse/jvm/update/bulk", BulkJvmsUpdateRequest.class, this::updateJvmBulk);
        dispatcher.requireAuth()
                .filter(c -> c.hasParam("analyse_id"), "Param 'analyse_id' is missing.")
                .filter(c -> c.hasParam("jvm_id"), "Param 'jvm_id' is missing.")
                .delete("/analyse/jvm/delete", this::deleteJvm);
        dispatcher.requireAuth()
                .filter(Restrictions.apply("/analyse/new", a ->
                        newAnalyses.get(a.id(), k -> analyseRepository.analysesCount(k).orElse(0))),
                        "You exceeded the amount of GC Analyse Groups per user. Contact us to increase this number.")
                .post("/analyse/new", NewAnalyseRequest.class, this::newAnalyse);
    }

    /**
     * GET /analyse/all
     * Require Auth (token)
     * Params: No
     * Responds: AnalysesResponse (JSON)
     */
    public void analyses(RequestContext ctx) {
        Identifier userId = account(ctx).id();
        ctx.response(new AnalysesResponse(
                analyseRepository.analysesFor(userId)));
    }

    /**
     * GET /analyse/get
     * Require Auth (token)
     * Params:
     *   - id (Identity of the analyse)
     * Responds: AnalyseResponse (JSON)
     */
    public void analyse(RequestContext ctx) {
        String id = ctx.param("id");
        Optional<GCAnalyse> oa = analyseRepository.analyse(account(ctx).id(), id);
        if (oa.isPresent()) {
            GCAnalyse analyse = oa.get();
            if (analyse.accountId().equals(account(ctx).id())) {
                ctx.response(new AnalyseResponse(analyse));
            } else {
                ctx.write(ErrorMessages.buildJson(ErrorMessages.RESOURCE_NOT_FOUND_RESPONSE));
            }
        } else {
            ctx.write(ErrorMessages.buildJson(ErrorMessages.RESOURCE_NOT_FOUND_RESPONSE));
        }
    }

    /**
     * POST /analyse/update
     * Require Auth (token)
     * Body: UpdateAnalyseRequest (JSON)
     * Responds: SUCCESS or ERROR
     */
    public void updateAnalyse(UpdateAnalyseRequest req, RequestContext ctx) {
        analyseRepository.perform(new UpdateAnalyseOperation(account(ctx).id(), req.id, req.name,
                req.timezone, req.ext));
        ctx.response(SUCCESS);
    }

    /**
     * POST /analyse/jvm/add
     * Require Auth (token)
     * Body: AddJvmRequest (JSON)
     * Responds: SUCCESS or ERROR
     */
    public void addJvm(AddJvmRequest req, RequestContext ctx) {
        Identifier userId = account(ctx).id();
        MemoryDetails md = null;
        if (req.memoryStatus != null) {
            md = new MemoryDetailsImpl(req.memoryStatus.pageSize,
                    req.memoryStatus.physicalTotal, req.memoryStatus.physicalFree,
                    req.memoryStatus.swapTotal, req.memoryStatus.swapFree);
        }
        analyseRepository.perform(new AddJvmOperation(userId, req.analyseId, req.jvmId, req.jvmName,
                VMVersion.get(req.vmVersion), GarbageCollectorType.get(req.gcType), req.headers, md));
        ctx.response(SUCCESS);
    }

    /**
     * DELETE /analyse/jvm/delete
     * Require Auth (token)
     * Params:
     *   - analyse_id (an ID of the GCAnalyse)
     *   - jvm_id (an ID of particular JVM inside GCAnalyse)
     * Responds: SUCCESS or ERROR
     */
    public void deleteJvm(RequestContext ctx) {
        String analyseId = ctx.param("analyse_id");
        String jvmId = ctx.param("jvm_id");

        analyseRepository.perform(new RemoveJvmOperation(account(ctx).id(), analyseId, jvmId));
        ctx.response(SUCCESS);
    }

    /**
     * POST /analyse/jvm/update/version
     * Require Auth (token)
     * Body: UpdateJvmVersionRequest (JSON)
     * Responds: SUCCESS or ERROR
     */
    public void updateJvmVersion(UpdateJvmVersionRequest req, RequestContext ctx) {
        VMVersion vmVersion = null;
        GarbageCollectorType gcType = null;
        if (req.vmVersion != null) {
            vmVersion = VMVersion.get(req.vmVersion);
        }
        if (req.gcType != null) {
            gcType = GarbageCollectorType.get(req.gcType);
        }
        analyseRepository.perform(new UpdateJvmVersionOperation(account(ctx).id(), req.analyseId, req.jvmId,
                req.jvmName, vmVersion, gcType));
        ctx.response(SUCCESS);
    }

    /**
     * POST /analyse/jvm/update/info
     * Require Auth (token)
     * Body: UpdateJvmInfoRequest (JSON)
     * Responds: SUCCESS or ERROR
     */
    public void updateJvmInfo(UpdateJvmInfoRequest req, RequestContext ctx) {
        analyseRepository.perform(new UpdateJvmInfoOperation(account(ctx).id(), req.analyseId, req.jvmId, req.headers,
                req.memoryStatus != null ? req.memoryStatus.toDetails() : null));
        ctx.response(SUCCESS);
    }

    /**
     * POST /analyse/jvm/update/bulk
     * Require Auth (token)
     * Body: BulkJvmsUpdateRequest (JSON)
     * Responds: SUCCESS or ERROR
     */
    public void updateJvmBulk(BulkJvmsUpdateRequest req, RequestContext ctx) {
        Identifier accId = account(ctx).id();
        List<AnalyseOperation> ops = new ArrayList<>();
        if (req.updateJvms != null) {
            req.updateJvms.forEach(r ->
                    ops.add(new UpdateJvmVersionOperation(accId, req.analyseId, r.jvmId, r.jvmName,
                            r.vmVersion != null ? VMVersion.get(r.vmVersion) : null,
                            r.gcType != null ? GarbageCollectorType.get(r.gcType) : null)));
        }
        if (req.removeJvms != null) {
            req.removeJvms.forEach(jvm ->
                    ops.add(new RemoveJvmOperation(accId, req.analyseId, jvm)));
        }
        if (req.newJvms != null) {
            req.newJvms.forEach(r ->
                    ops.add(new AddJvmOperation(accId, req.analyseId, r.jvmId, r.jvmName, VMVersion.get(r.vmVersion),
                    GarbageCollectorType.get(r.gcType), r.headers,
                    r.memoryStatus != null ? r.memoryStatus.toDetails() : null)));
        }
        if (ops.size() > 0) {
            analyseRepository.perform(ops);
        }
        ctx.response(SUCCESS);
    }

    /**
     * DELETE /analyse/delete
     * Require Auth (token)
     * Params:
     *   - id (Identity of the analyse)
     * Responds: SUCCESS or ERROR
     */
    public void deleteAnalyse(RequestContext ctx) {
        String id = ctx.param("id");
        analyseRepository.perform(new RemoveAnalyseOperation(account(ctx).id(), id));
        ctx.response(SUCCESS);
    }

    /**
     * POST /analyse/new
     * Require Auth (token)
     * Body: NewAnalyseRequest (JSON)
     * Responds: NewAnalyseResponse (JSON)
     */
    public void newAnalyse(NewAnalyseRequest req, RequestContext ctx) {
        Identifier userId = account(ctx).id();
        GCAnalyseImpl analyse = new GCAnalyseImpl().name(req.name).isContinuous(req.isContinuous).accountId(userId)
                .start(DateTime.now(DateTimeZone.UTC)).ext(req.ext).jvmHeaders(Collections.emptyMap())
                .jvmMemoryDetails(Collections.emptyMap()).timezone(req.timezone);
        if (req.jvms == null || req.jvms.size() == 0) {
            analyse.jvmGCTypes(Collections.emptyMap()).jvmVersions(Collections.emptyMap())
                    .jvmIds(Collections.emptySet()).jvmNames(Collections.emptyMap());
        } else {
            Set<String> jvmIds = new HashSet<>();
            Map<String, String> jvmNames = new HashMap<>();
            Map<String, GarbageCollectorType> jvmGCTypes = new HashMap<>();
            Map<String, VMVersion> jvmVersions = new HashMap<>();
            req.jvms.forEach(jvm -> {
                jvmIds.add(jvm.jvmId);
                jvmNames.put(jvm.jvmId, jvm.jvmName);
                jvmGCTypes.put(jvm.jvmId, GarbageCollectorType.get(jvm.gcType));
                jvmVersions.put(jvm.jvmId, VMVersion.get(jvm.vmVersion));
            });
            analyse.jvmGCTypes(jvmGCTypes).jvmVersions(jvmVersions).jvmIds(jvmIds).jvmNames(jvmNames);
        }

        ctx.response(new NewAnalyseResponse(analyseRepository.newAnalyse(analyse)));
        newAnalyses.invalidate(userId);
    }

    @Autowired
    protected GCAnalyseRepository analyseRepository;
}
