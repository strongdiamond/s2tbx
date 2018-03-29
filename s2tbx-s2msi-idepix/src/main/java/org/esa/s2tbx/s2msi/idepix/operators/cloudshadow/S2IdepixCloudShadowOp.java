package org.esa.s2tbx.s2msi.idepix.operators.cloudshadow;

import com.bc.ceres.core.ProgressMonitor;
import org.esa.snap.core.datamodel.Product;
import org.esa.snap.core.gpf.GPF;
import org.esa.snap.core.gpf.Operator;
import org.esa.snap.core.gpf.OperatorException;
import org.esa.snap.core.gpf.OperatorSpi;
import org.esa.snap.core.gpf.annotations.OperatorMetadata;
import org.esa.snap.core.gpf.annotations.Parameter;
import org.esa.snap.core.gpf.annotations.SourceProduct;
import org.esa.snap.core.gpf.annotations.TargetProduct;
import org.esa.snap.core.gpf.internal.OperatorExecutor;

import java.util.HashMap;
import java.util.Map;

/**
 * todo: add comment
 */
@OperatorMetadata(alias = "CCICloudShadow",
        category = "Optical",
        authors = "Grit Kirches, Michael Paperin, Olaf Danne",
        copyright = "(c) Brockmann Consult GmbH",
        version = "1.0",
        description = "Algorithm detecting cloud shadow...")
public class S2IdepixCloudShadowOp extends Operator {

    @SourceProduct(description = "The classification product.")
    private Product s2ClassifProduct;

    @SourceProduct(alias = "s2CloudBuffer", optional = true)
    private Product s2CloudBufferProduct;      // has only classifFlagBand with buffer added

    @TargetProduct
    private Product targetProduct;

    @Parameter(description = "The mode by which clouds are detected. There are three options: Land/Water, Multiple Bands" +
            "or Single Band", valueSet = {"LandWater", "MultiBand", "SingleBand"}, defaultValue = "LandWater")
    private String mode;

    @Parameter(description = "Whether to also compute mountain shadow", defaultValue = "true")
    private boolean computeMountainShadow;

    public final static String BAND_NAME_CLOUD_SHADOW = "FlagBand";

    @Override
    public void initialize() throws OperatorException {
        HashMap<String, Product> preInput = new HashMap<>();
        preInput.put("s2ClassifProduct", s2ClassifProduct);
        preInput.put("s2CloudBufferProduct", s2CloudBufferProduct);
        Map<String, Object> preParams = new HashMap<>();
        preParams.put("computeMountainShadow", computeMountainShadow);
        preParams.put("mode", mode);

        final String operatorAlias = OperatorSpi.getOperatorAlias(S2IdepixPreCloudShadowOp.class);
        final Operator cloudShadowPreProcessingOperator =
                GPF.getDefaultInstance().createOperator(operatorAlias, preParams, preInput, null);

        //trigger computation of all tiles
        final OperatorExecutor operatorExecutor = OperatorExecutor.create(cloudShadowPreProcessingOperator);
        operatorExecutor.execute(ProgressMonitor.NULL);

        Product preProcessedProduct = cloudShadowPreProcessingOperator.getTargetProduct();
        //here you could retrieve the important information from the preProcessedProduct

        HashMap<String, Product> postInput = new HashMap<>();
        postInput.put("source", preProcessedProduct);
        //put in here the input products that are required by the post-processing operator
        Map<String, Object> postParams = new HashMap<>();
        //put in here any parameters that might be requested by the post-processing operator
        targetProduct = GPF.createProduct(OperatorSpi.getOperatorAlias(S2IdepixPostCloudShadowOp.class),
                                          postParams, postInput);
        setTargetProduct(targetProduct);
    }

    public static class Spi extends OperatorSpi {

        public Spi() {
            super(S2IdepixCloudShadowOp.class);
        }
    }

}
