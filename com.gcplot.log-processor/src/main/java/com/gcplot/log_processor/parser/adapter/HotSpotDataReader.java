package com.gcplot.log_processor.parser.adapter;

import com.tagtraum.perf.gcviewer.imp.DataReaderSun1_6_0;
import com.tagtraum.perf.gcviewer.imp.GcLogType;
import com.tagtraum.perf.gcviewer.model.AbstractGCEvent;
import com.tagtraum.perf.gcviewer.model.GCModel;
import com.tagtraum.perf.gcviewer.model.GCResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.function.Consumer;

/**
 * @author <a href="mailto:art.dm.ser@gmail.com">Artem Dmitriev</a>
 *         8/7/16
 */
public class HotSpotDataReader extends DataReaderSun1_6_0 implements StreamDataReader {
    private final Consumer<AbstractGCEvent<?>> eventConsumer;
    private final int batchSize;

    public HotSpotDataReader(Consumer<AbstractGCEvent<?>> eventConsumer, int batchSize,
                             GCResource gcResource, InputStream in, GcLogType gcLogType)
            throws UnsupportedEncodingException {
        super(gcResource, in, gcLogType);
        this.eventConsumer = eventConsumer;
        this.batchSize = batchSize;
    }

    @Override
    protected GCModel createGCModel() {
        StreamGCModel model = new StreamGCModel();
        model.setEventsConsumer(l -> l.forEach(eventConsumer));
        if (batchSize > 0) {
            model.setBatchSize(batchSize);
        }
        return model;
    }

    @Override
    public StreamGCModel readStream() throws IOException {
        return (StreamGCModel) read();
    }

    @Override
    public void excludedHandler(Consumer<String> c) {
        setExcludedHandler(c);
    }

    @Override
    public void headerHandler(Consumer<String> c) {
        setHeaderHandler(c);
    }
}
