package org.esa.s2tbx.dataio.gdal.writer.plugins;

/**
 * @author Jean Coravu
 */
public class NITFDriverProductWriterPlugInTest extends AbstractTestDriverProductWriterPlugIn {

    public NITFDriverProductWriterPlugInTest() {
        super("NITF", new NITFDriverProductWriterPlugIn());
    }
}
