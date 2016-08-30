package com.gcplot;

import com.gcplot.commons.ErrorMessages;
import com.gcplot.commons.serialization.JsonSerializer;
import com.gcplot.messages.*;
import com.gcplot.model.VMVersion;
import com.gcplot.model.gc.GarbageCollectorType;
import com.gcplot.model.gc.MemoryDetailsImpl;
import com.google.common.collect.Sets;
import io.vertx.core.json.JsonObject;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.HttpClientBuilder;
import org.junit.Assert;
import org.junit.Test;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * @author <a href="mailto:art.dm.ser@gmail.com">Artem Dmitriev</a>
 *         8/23/16
 */
public class GCTests extends IntegrationTest {

    @Test
    public void test() throws Exception {
        String token = login();
        NewAnalyseRequest nar = new NewAnalyseRequest("analyse1", false, "");
        final String[] analyseId = { "" };
        get("/analyse/all", token, j -> r(j).getJsonArray("analyses").size() == 0);
        post("/analyse/new", nar, token, j -> {
            analyseId[0] = r(j).getString("id");
            return r(j).getString("id") != null;
        });
        Assert.assertNotNull(analyseId[0]);

        get("/analyse/all", token, j -> r(j).getJsonArray("analyses").size() == 1);
        JsonObject analyseJson = get("/analyse/get?id=" + analyseId[0], token, a -> true);
        Assert.assertNotNull(analyseJson);
        Assert.assertEquals(analyseId[0], r(analyseJson).getString("id"));
        Assert.assertEquals("analyse1", r(analyseJson).getString("name"));
        Assert.assertEquals(false, r(analyseJson).getBoolean("cnts"));
        Assert.assertTrue(r(analyseJson).getJsonArray("jvm_ids").isEmpty());
        Assert.assertTrue(r(analyseJson).getJsonObject("jvm_hdrs").isEmpty());
        Assert.assertTrue(r(analyseJson).getJsonObject("jvm_vers").isEmpty());
        Assert.assertTrue(r(analyseJson).getJsonObject("jvm_gcts").isEmpty());
        Assert.assertTrue(r(analyseJson).getJsonObject("jvm_mem").isEmpty());

        get("/analyse/get?id=123", token, ErrorMessages.INTERNAL_ERROR);
        get("/analyse/get?id=" + UUID.randomUUID().toString(), token, ErrorMessages.RESOURCE_NOT_FOUND_RESPONSE);

        post("/analyse/jvm/add", new AddJvmRequest("jvm1", analyseId[0], VMVersion.HOTSPOT_1_9.type(),
                GarbageCollectorType.ORACLE_SERIAL.type(), "h1,h2", null), token, success());
        post("/analyse/jvm/add", new AddJvmRequest("jvm2", analyseId[0], VMVersion.HOTSPOT_1_3_1.type(),
                GarbageCollectorType.ORACLE_PAR_OLD_GC.type(), null, new MemoryStatus(new MemoryDetailsImpl(1,2,3,4,5))),
                token, success());

        analyseJson = get("/analyse/get?id=" + analyseId[0], token, a -> true);
        AnalyseResponse ar = JsonSerializer.deserialize(r(analyseJson).toString(), AnalyseResponse.class);
        Assert.assertEquals(analyseId[0], ar.id);
        Assert.assertEquals(Sets.newHashSet("jvm1", "jvm2"), ar.jvmIds);
        Assert.assertEquals(VMVersion.HOTSPOT_1_9.type(), (int) ar.jvmVersions.get("jvm1"));
        Assert.assertEquals(VMVersion.HOTSPOT_1_3_1.type(), (int) ar.jvmVersions.get("jvm2"));
        Assert.assertEquals(GarbageCollectorType.ORACLE_SERIAL.type(), (int) ar.jvmGCTypes.get("jvm1"));
        Assert.assertEquals(GarbageCollectorType.ORACLE_PAR_OLD_GC.type(), (int) ar.jvmGCTypes.get("jvm2"));
        Assert.assertEquals("h1,h2", ar.jvmHeaders.get("jvm1"));
        Assert.assertNull(ar.jvmHeaders.get("jvm2"));
        Assert.assertEquals(new MemoryStatus(1,2,3,4,5), ar.memory.get("jvm2"));
        Assert.assertNull(ar.memory.get("jvm1"));

        delete("/analyse/delete?id=" + UUID.randomUUID().toString(), token);
        get("/analyse/all", token, j -> r(j).getJsonArray("analyses").size() == 1);
        Assert.assertEquals(1, (int) r(delete("/analyse/delete?id=" + analyseId[0], token)).getInteger("success"));
        get("/analyse/all", token, j -> r(j).getJsonArray("analyses").size() == 0);
    }

    @Test
    public void simpleLogsUploadTest() throws Exception {
        HttpClient hc = HttpClientBuilder.create().build();
        HttpEntity file = MultipartEntityBuilder.create()
                .addBinaryBody("gc.log", GCTests.class.getClassLoader().getResourceAsStream("hs18_log_cms.log"),
                        ContentType.TEXT_PLAIN, "hs18_log_cms.log").build();
        HttpPost post = new HttpPost("http://" + LOCALHOST + ":" + getPort() + "/gc/upload_log?token=" + login());
        post.setEntity(file);
        HttpResponse response = hc.execute(post);
        Assert.assertEquals(200, response.getStatusLine().getStatusCode());
    }

    private String login() throws Exception {
        RegisterRequest request = new RegisterRequest("admin", null, null, "root", "a@b.c");
        post("/user/register", request, jo -> jo.containsKey("result"));

        JsonObject jo = login(request);
        return jo.getString("token");
    }

    private Predicate<JsonObject> success() {
        return a -> r(a).getInteger("success").equals(1);
    }

}