package org.anc.lapps.nlp4j;

import edu.emory.mathcs.nlp.bin.NLPTrain;
import edu.emory.mathcs.nlp.component.template.feature.Field;
import edu.emory.mathcs.nlp.component.template.feature.Relation;
import edu.emory.mathcs.nlp.component.template.feature.Source;
import edu.emory.mathcs.nlp.component.template.util.NLPMode;
import org.apache.commons.lang3.EnumUtils;
import org.lappsgrid.api.ProcessingService;
import org.lappsgrid.discriminator.Discriminators;
import org.lappsgrid.metadata.IOSpecification;
import org.lappsgrid.metadata.ServiceMetadata;
import org.lappsgrid.serialization.Data;
import org.lappsgrid.serialization.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.PatternSyntaxException;

/**
 * @author Alexandru Mahmoud
 */


public class NLP4JTrain implements ProcessingService
{
    /**
     * The Json String required by getMetadata()
     */
    private String metadata;
    private Logger logger;

    public NLP4JTrain() { metadata = generateMetadata(); }

    private String generateMetadata()
    {
        ServiceMetadata metadata = new ServiceMetadata();
        metadata.setName(this.getClass().getName());
        metadata.setDescription("The NLP4J project provides a NLP toolkit for JVM languages.");
        metadata.setVersion(Version.getVersion());
        metadata.setVendor("http://www.lappsgrid.org");
        metadata.setLicense(Discriminators.Uri.APACHE2);

        IOSpecification requires = new IOSpecification();
        requires.addFormat(Discriminators.Uri.GET);
        requires.setEncoding("UTF-8");

        IOSpecification produces = new IOSpecification();
        produces.addFormat(Discriminators.Uri.LAPPS);
        produces.setEncoding("UTF-8");

        metadata.setRequires(requires);
        metadata.setProduces(produces);

        Data<ServiceMetadata> data = new Data<>();
        data.setDiscriminator(Discriminators.Uri.META);
        data.setPayload(metadata);
        return data.asPrettyJson();
    }

    @Override
    /**
     * Returns a JSON string containing metadata describing the service. The
     * JSON <em>must</em> conform to the json-schema at
     * <a href="http://vocab.lappsgrid.org/schema/service-schema.json">http://vocab.lappsgrid.org/schema/service-schema.json</a>
     * (processing services) or
     * <a href="http://vocab.lappsgrid.org/schema/datasource-schema.json">http://vocab.lappsgrid.org/schema/datasource-schema.json</a>
     * (datasources).
     */
    public String getMetadata() {
        return metadata;
    }

    /**
     * Entry point for a Lappsgrid service.
     * <p>
     * Each service on the Lappsgrid will accept {@code org.lappsgrid.serialization.Data} object
     * and return a {@code Data} object with a {@code org.lappsgrid.serialization.lif.Container}
     * payload.
     * <p>
     * Errors and exceptions that occur during processing should be wrapped in a {@code Data}
     * object with the discriminator set to http://vocab.lappsgrid.org/ns/error
     * <p>
     * See <a href="https://lapp.github.io/org.lappsgrid.serialization/index.html?org/lappsgrid/serialization/Data.html>org.lappsgrid.serialization.Data</a><br />
     * See <a href="https://lapp.github.io/org.lappsgrid.serialization/index.html?org/lappsgrid/serialization/lif/Container.html>org.lappsgrid.serialization.lif.Container</a><br />
     *
     * @param input A JSON string representing a Data object
     * @return A JSON string containing a Data object with a Container payload.
     */
    @Override
    public String execute(String input) {

        logger = LoggerFactory.getLogger(NLP4JTrain.class);

        // Parse the JSON string into a Data object, and extract its discriminator.
        Data<String> data = Serializer.parse(input, Data.class);
        String discriminator = data.getDiscriminator();

        // If the Input discriminator is ERROR, return the Data as is, since it's already a wrapped error.
        if (Discriminators.Uri.ERROR.equals(discriminator))
        {
            return input;
        }

        // If the Input discriminator is not GET, return a wrapped Error with an appropriate message.
        else if (!Discriminators.Uri.GET.equals(discriminator))
        {
            String errorData = generateError("Invalid discriminator.\nExpected " + Discriminators.Uri.GET + "\nFound " + discriminator);
            logger.error(errorData);
            return errorData;
        }

        // Output an error if no payload is given, since an input is required to run the program
        if (data.getPayload() == null)
        {
            String errorData = generateError("No input given.");
            logger.error(errorData);
            return errorData;
        }

        // Else (if a payload is given), process the input
        else
        {
            // Create temporary directories to hold input and output. This is needed because
            // the RankLib methods need directories for most of their processing, so the input
            // will be given within files in a directory, and the output will be read from files
            // in the output directory.
            Path outputDirPath = null;
            Path inputDirPath = null;
            try
            {
                outputDirPath = Files.createTempDirectory("output");
                outputDirPath.toFile().deleteOnExit();
                inputDirPath = Files.createTempDirectory("input");
                inputDirPath.toFile().deleteOnExit();
            }
            // Since we are only handling files created by the function, there should never be
            // a problem with these files, thus the exception will get promoted to a RuntimeException.
            catch (IOException e)
            {
                String errorData = generateError("Error in handling of temporary files.");
                logger.error(errorData);
                throw new RuntimeException("A problem occurred in the handling of the temporary files.", e);
            }

            StringBuilder params = new StringBuilder("-c ");

            try
            {
                String configPath = makeConfigFile(inputDirPath, data);
                if(configPath.contains("ERROR"))
                {
                    if(configPath.contains("INDEX ERROR"))
                    {
                        StringBuilder errorMsg = new StringBuilder("The given list of TSV indices and TSV fields did not match.\r\n");
                        String[] errorParts;
                        errorParts = configPath.split(";");
                        errorMsg.append("Given indices: ").append(errorParts[1]);
                        errorMsg.append("\r\nGiven fields: ").append(errorParts[2]);

                        String errorData = generateError(errorMsg.toString());
                        logger.error(errorData);
                        return errorData;
                    }

                    else if(configPath.contains("AMBIGUITY ERROR"))
                    {
                        StringBuilder errorMsg = new StringBuilder("Invalid field given for ambiguity classes.\r\n");
                        String[] errorParts;
                        errorParts = configPath.split(";");
                        errorMsg.append("Given: ").append(errorParts[1]);

                        String errorData = generateError(errorMsg.toString());
                        logger.error(errorData);
                        return errorData;
                    }

                    else if(configPath.contains("CLUSTERS ERROR"))
                    {
                        StringBuilder errorMsg = new StringBuilder("Invalid field given for word clusters.\r\n");
                        String[] errorParts;
                        errorParts = configPath.split(";");
                        errorMsg.append("Given: ").append(errorParts[1]);

                        String errorData = generateError(errorMsg.toString());
                        logger.error(errorData);
                        return errorData;
                    }

                    else if(configPath.contains("NAMED ENTITY ERROR"))
                    {
                        StringBuilder errorMsg = new StringBuilder("Invalid field given for named entity gazetteers.\r\n");
                        String[] errorParts;
                        errorParts = configPath.split(";");
                        errorMsg.append("Given: ").append(errorParts[1]);

                        String errorData = generateError(errorMsg.toString());
                        logger.error(errorData);
                        return errorData;
                    }

                    else if(configPath.contains("EMBEDDINGS ERROR"))
                    {
                        StringBuilder errorMsg = new StringBuilder("Invalid field given for word embeddings.\r\n");
                        String[] errorParts;
                        errorParts = configPath.split(";");
                        errorMsg.append("Given: ").append(errorParts[1]);

                        String errorData = generateError(errorMsg.toString());
                        logger.error(errorData);
                        return errorData;
                    }

                    else if(configPath.contains("ALGORITHM ERROR"))
                    {
                        StringBuilder errorMsg = new StringBuilder("Invalid name given for optimizer algorithm.\r\n");
                        String[] errorParts;
                        errorParts = configPath.split(";");
                        errorMsg.append("Given: ").append(errorParts[1]);

                        String errorData = generateError(errorMsg.toString());
                        logger.error(errorData);
                        return errorData;
                    }

                    else if(configPath.contains("INVALID FEATURE SOURCE ERROR"))
                    {
                        StringBuilder errorMsg = new StringBuilder("Invalid source given for feature.\r\n");
                        String[] errorParts;
                        errorParts = configPath.split(";");
                        errorMsg.append("Given: ").append(errorParts[1]);
                        errorMsg.append("\r\nFeature line number: ").append(errorParts[2]);
                        errorMsg.append("\r\nFeature number: f").append(errorParts[3]);

                        String errorData = generateError(errorMsg.toString());
                        logger.error(errorData);
                        return errorData;
                    }

                    else if(configPath.contains("INVALID FEATURE RELATION ERROR"))
                    {
                        StringBuilder errorMsg = new StringBuilder("Invalid relation given for feature.\r\n");
                        String[] errorParts;
                        errorParts = configPath.split(";");
                        errorMsg.append("Given: ").append(errorParts[1]);
                        errorMsg.append("\r\nFeature line number: ").append(errorParts[2]);
                        errorMsg.append("\r\nFeature number: f").append(errorParts[3]);

                        String errorData = generateError(errorMsg.toString());
                        logger.error(errorData);
                        return errorData;
                    }

                    else if(configPath.contains("INVALID FEATURE FIELD ERROR"))
                    {
                        StringBuilder errorMsg = new StringBuilder("Invalid field given for feature.\r\n");
                        String[] errorParts;
                        errorParts = configPath.split(";");
                        errorMsg.append("Given: ").append(errorParts[1]);
                        errorMsg.append("\r\nFeature line number: ").append(errorParts[2]);
                        errorMsg.append("\r\nFeature number: f").append(errorParts[3]);

                        String errorData = generateError(errorMsg.toString());
                        logger.error(errorData);
                        return errorData;
                    }

                    else
                    {
                        StringBuilder errorMsg = new StringBuilder("Unknown error found in configuration parameters.\r\n");
                        errorMsg.append("String returned: ").append(configPath);

                        String errorData = generateError(errorMsg.toString());
                        logger.error(errorData);
                        return errorData;
                    }
                }

                // Call the method that converts the parameters to the format that they would
                // be in when given from command-line.
                params.append(configPath);

                String convertedParams = convertParameters(data, outputDirPath, inputDirPath).replace("\\", "/");

                if(convertedParams.contains("ERROR"))
                {
                    if(convertedParams.contains("MODE ERROR"))
                    {
                        StringBuilder errorMsg = new StringBuilder("Invalid mode parameter given.\r\n");
                        String[] errorParts;
                        errorParts = configPath.split(";");
                        errorMsg.append("Given: ").append(errorParts[1]);

                        String errorData = generateError(errorMsg.toString());
                        logger.error(errorData);
                        return errorData;
                    }
                }

                params.append(convertedParams);
            }
            // Since we are only handling files created by the function, there should never be
            // a problem with these files, thus the exception will get promoted to a RuntimeException.
            catch (IOException e)
            {
                String errorData = generateError("Error in handling of temporary files.");
                logger.error(errorData);
                throw new RuntimeException("A problem occurred in the handling of the temporary files.", e);
            }
            String[] paramsArray;

            // Split the parameters into an array, which will be given as the args[] argument
            // to the main methods of RankLib.
            try { paramsArray = params.toString().split("\\s+"); }
            catch (PatternSyntaxException ex)
            {
                String errorData = generateError("Error in parameter syntax.");
                logger.error(errorData);
                return errorData;
            }

            // Create a stream to hold the output from System.out.println. This is necessary
            // because when running, the program will print things from many RankLib classes and
            // methods. So the printed output will be "caught" and saved to output.
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintStream ps = new PrintStream(baos);
            // Save the old System.out PrintStream, to reset at the end of the program.
            PrintStream oldPrintStream = System.out;
            // Set the special stream as the out stream
            System.setOut(ps);

            NLPTrain.main(paramsArray);

            // Set System.out back to the original PrintStream
            System.out.flush();
            System.setOut(oldPrintStream);

            // Make a Map to hold both the printed, and file outputs.
            Map<String,String> outputPayload = new HashMap<>();

            String finalPrint;

            if(data.getParameter("saveModel") != null)
            {
                StringBuilder toRemove = new StringBuilder("Name not implemented for OnlineComponent. Input name - ");
                toRemove.append(data.getParameter("saveModel")).append(" will be ignored.");
                finalPrint = baos.toString().replace(toRemove.toString(), "");
            }

            else
            {
                finalPrint = baos.toString();
            }
            // Add the printed text caught from the out stream to the payload
            // with the "Printed" key

            outputPayload.put("Printed", finalPrint);

            // Parse the Map to Json, then put it as a payload to a Data object with a LAPPS
            // discriminator and return it as the final output
            String outputJson = Serializer.toJson(outputPayload);
            Data<String> output = new Data<>(Discriminators.Uri.LAPPS, outputJson);
            return output.asPrettyJson();
        }

    }


    /** This method takes in the input data and returns its parameters as an array of strings,
     * representing the parameters as they would be written to run the jar files from command-line,
     * to be given as input to the main classes.
     *
     * @param data A Data object
     * @param outputDirPath A Path to the output directory
     * @param inputDirPath A Path to the input directory
     * @return A String representing the parameters of the Data object.
     */
    private String convertParameters(Data<String> data, Path outputDirPath, Path inputDirPath) throws IOException
    {
        StringBuilder params = new StringBuilder();

        // Get the payload and convert it back into a HashMap to get all input content from it.
        String payloadJson = data.getPayload();
        Map<String,String> payload = Serializer.parse(payloadJson, HashMap.class);

        // This boolean for development will be set to true if at least one of the keys corresponds
        // to a development file. This is needed since this is an optional parameter that might take
        // multiple input files.
        boolean developBool = false;

        // These are the parameters for training that give input.
        // Since the input can include many train and development files, we process
        // all keys expecting their labels to include "train", "dev", or "config."
        for (String key : payload.keySet())
        {
            // If the input file is a train file, we take its content and save it to a
            // temporary file in the input directory. The entire directory will be given
            // as the train directory path, and the extension ".trn" will be specified
            // to distinguish the train files.
            if (key.contains("train"))
            {
                String fileContent = payload.get(key);
                writeTempFile(key, inputDirPath, fileContent, ".trn");
            }

            // If the input file is a development file, we take its content and save it to a
            // temporary file in the input directory, after setting the boolean to true.
            // The entire directory will be given as the development directory path and the
            // extension ".dev" will be specified to distinguish the development files.
            else if (key.contains("develop"))
            {
                developBool = true;
                String fileContent = payload.get(key);
                writeTempFile(key, inputDirPath, fileContent, ".dev");
            }

            // TODO: Add prev for previously trained models: -p
        }

        // Add the train directory parameter with the input directory path as an argument,
        // and specify the "trn" extension for train files.
        params.append(" -t ").append(inputDirPath).append(" -te trn");

        // If the boolean is set to true, add the development directory parameter with the
        // input directory path as an argument, and specify the "dev" extension for train files.
        if(developBool)
        {
            params.append(" -d ").append(inputDirPath).append(" -de dev");
        }

        if(data.getParameter("mode") != null)
        {
            String givenMode = (String) data.getParameter("mode");

            if(!EnumUtils.isValidEnum(NLPMode.class, givenMode))
            {
                StringBuilder errorMsg = new StringBuilder("MODE ERROR;");
                errorMsg.append(givenMode);
                return errorMsg.toString();
            }
            params.append(" -mode ").append(data.getParameter("mode"));
        }

        // "Name not implemented for Online component."
        if(data.getParameter("saveModel") != null)
        {
            params.append(" -m ").append(outputDirPath).append("/").append(data.getParameter("saveModel")).append(".xz");
        }

        if(data.getParameter("cv") != null)
        {
            params.append(" -cv ").append(data.getParameter("cv"));
        }

        // Return the resulting list of parameters to be processed as an array
        // and given as input to the NLP4J Train main method.
        return params.toString();
    }

    /** This method creates the appropriate configuration file in the given temporary input
     * directory, and returns the path to that file as a String.
     *
     * @param dir The path to input directory in which the configuration file should be created
     * @param inputData The input data from which to extract configuration details
     * @return A String representing the path to the created configuration file.
     */
    public String makeConfigFile(Path dir, Data<String> inputData) throws IOException
    {
        // This will hold the text for the configuration file, which is in XML format.
        StringBuilder configTxt = new StringBuilder("<configuration>\r\n");


        // START OF TSV FORMAT
        // Create an array of strings that will hold the field indices, if they are specified
        String[] tsvIndices = null;
        String indicesString = "";

        // Split the indices by commas, and remove whitespaces to have an array representing
        // all indices for the corresponding fields
        if (inputData.getParameter("tsv-indices") != null)
        {
            indicesString = (String) inputData.getParameter("tsv-indices");
            tsvIndices = indicesString.split(",[ ]*");
        }

        if (inputData.getParameter("tsv-fields") != null)
        {
            String fieldsString = (String) inputData.getParameter("tsv-fields");
            // Split and remove white spaces
            String[] tsvFields = fieldsString.split(",[ ]*");
            configTxt.append("    <tsv>\r\n");

            // If the indices are specified, add each field to its respective index
            if (tsvIndices != null)
            {
                String index, field;
                for (int i = 0; i < tsvFields.length; i++)
                {
                    try
                    {
                        index = tsvIndices[i];
                        field = tsvFields[i];
                    }

                    // If any field does not have a corresponding index, and the indices
                    // are specified, return a string specifying that there is an error,
                    // which will be handled by the execute function after returning.
                    // (We return a String here and then handle the error in execute in
                    // order to wrap the error appropriately before returning it to the user)
                    catch (IndexOutOfBoundsException e)
                    {
                        StringBuilder errorMsg = new StringBuilder("INDEX ERROR;");
                        errorMsg.append(indicesString).append(";").append(fieldsString);
                        return errorMsg.toString();
                    }

                    configTxt.append("        <column index=\"").append(index);
                    configTxt.append("\" field=\"").append(field).append("\"/>\r\n");
                }
            }

            // If the indices are not specified, assume the fields are listed in the order
            // they appear in the tsv file, starting with index 0. In this case, go through the
            // fields and use the index of each field as its index in the configuration file.
            else
            {
                for (int i = 0; i < tsvFields.length; i++)
                {
                    String field = tsvFields[i];
                    configTxt.append("        <column index=\"").append(i);
                    configTxt.append("\" field=\"").append(field).append("\"/>\r\n");
                }
            }
            configTxt.append("    </tsv>\r\n\r\n");
        }
        // END OF TSV FORMAT

        // START OF LEXICA
        // This is the path for all lexica dependent files
        String lexicaPath = "src/main/resources/lexica/";

        // A boolean that will be set to true when the first lexical parameter is set.
        // This is needed because we don't know which, if any, of the components will be
        // specified, thus we don't know if the lexica header is needed.
        boolean lexicaSet = false;

        if(inputData.getParameter("ambiguity") != null)
        {
            // Three arrays holding the names, which users can choose from, and their
            // corresponding filenames and field names
            String[] ambiguityNames = {"simplified","simplified-lowercase"};
            String[] ambiguityFiles = {"en-ambiguity-classes-simplified.xz", "en-ambiguity-classes-simplified-lowercase.xz"};
            String[] ambiguityFields = {"word_form_simplified","word_form_simplified_lowercase"};

            if(!lexicaSet)
            {
                lexicaSet = true;
                configTxt.append("    <lexica>\r\n");
            }

            String givenName = (String) inputData.getParameter("ambiguity");
            StringBuilder ambiguityTxt = null;
            int index = 0;

            // Loop through the possible names and complete the corresponding XML format for the
            // configuration file using the index that matches the given name
            while((ambiguityTxt == null) && (index < ambiguityNames.length))
            {
                if(ambiguityNames[index] == givenName)
                {
                    ambiguityTxt = new StringBuilder("        <ambiguity_classes field=\"");
                    ambiguityTxt.append(ambiguityFields[index]).append("\">").append(lexicaPath);
                    ambiguityTxt.append(ambiguityFiles[index]).append("</ambiguity_classes>\r\n");
                }
                index++;
            }

            // If no names matches, the user gave an unknown name, thus return a string mentioning the error
            // which will be handled in the execute method and properly wrapped as an error data object
            // to be returned to the user
            if(ambiguityTxt == null)
            {
                StringBuilder errorMsg = new StringBuilder("AMBIGUITY ERROR;");
                errorMsg.append(givenName);
                return errorMsg.toString();
            }

            else
            {
                configTxt.append(ambiguityTxt);
            }
        }


        if(inputData.getParameter("clusters") != null)
        {
            // Three arrays holding the names, which users can choose from, and their
            // corresponding filenames and field names
            String[] clustersNames = {"brown-simplified-lc","brown-twit-lc"};
            String[] clustersFiles = {"en-brown-clusters-simplified-lowercase.xz", "en-brown-clusters-twit-lowercase.xz"};
            String[] clustersFields = {"word_form_simplified_lowercase","word_form_lowercase"};

            if(!lexicaSet)
            {
                lexicaSet = true;
                configTxt.append("    <lexica>\r\n");
            }

            String givenName = (String) inputData.getParameter("clusters");
            StringBuilder clustersTxt = null;
            int index = 0;

            // Loop through the possible names and complete the corresponding XML format for the
            // configuration file using the index that matches the given name
            while((clustersTxt == null) && (index < clustersNames.length))
            {
                if(clustersNames[index] == givenName)
                {
                    clustersTxt = new StringBuilder("        <word_clusters field=\"");
                    clustersTxt.append(clustersFields[index]).append("\">").append(lexicaPath);
                    clustersTxt.append(clustersFiles[index]).append("</word_clusters>\r\n");
                }
                index++;
            }

            // If no names matches, the user gave an unknown name, thus return a string mentioning the error
            // which will be handled in the execute method and properly wrapped as an error data object
            // to be returned to the user
            if(clustersTxt == null)
            {
                StringBuilder errorMsg = new StringBuilder("CLUSTERS ERROR;");
                errorMsg.append(givenName);
                return errorMsg.toString();
            }

            else
            {
                configTxt.append(clustersTxt);
            }
        }


        if(inputData.getParameter("gazetteers") != null)
        {
            // Three arrays holding the names, which users can choose from, and their
            // corresponding filenames and field names
            String[] namedEntityNames = {"simplified","simplified-lowercase"};
            String[] namedEntityFiles = {"en-named-entity-gazetteers-simplified.xz", "en-named-entity-gazetteers-simplified-lowercase.xz"};
            String[] namedEntityFields = {"word_form_simplified","word_form_simplified_lowercase"};

            if(!lexicaSet)
            {
                lexicaSet = true;
                configTxt.append("    <lexica>\r\n");
            }

            String givenName = (String) inputData.getParameter("gazetteers");
            StringBuilder NETxt = null;
            int index = 0;

            // Loop through the possible names and complete the corresponding XML format for the
            // configuration file using the index that matches the given name
            while((NETxt == null) && (index < namedEntityNames.length))
            {
                if(namedEntityNames[index] == givenName)
                {
                    NETxt = new StringBuilder("        <named_entity_gazetteers field=\"");
                    NETxt.append(namedEntityFields[index]).append("\">").append(lexicaPath);
                    NETxt.append(namedEntityFiles[index]).append("</named_entity_gazetteers>\r\n");
                }
                index++;
            }

            // If no names matches, the user gave an unknown name, thus return a string mentioning the error
            // which will be handled in the execute method and properly wrapped as an error data object
            // to be returned to the user
            if(NETxt == null)
            {
                StringBuilder errorMsg = new StringBuilder("NAMED ENTITY ERROR;");
                errorMsg.append(givenName);
                return errorMsg.toString();
            }

            else
            {
                configTxt.append(NETxt);
            }
        }

        if(inputData.getParameter("embeddings") != null)
        {
            // Three arrays holding the names, which users can choose from, and their
            // corresponding filenames and field names
            String[] embeddingsNames = {"undigitalized"};
            String[] embeddingsFiles = {"en-word-embeddings-undigitalized.xz"};
            String[] embeddingsFields = {"word_form_undigitalized"};

            if(!lexicaSet) {
                lexicaSet = true;
                configTxt.append("    <lexica>\r\n");
            }

            String givenName = (String) inputData.getParameter("embeddings");
            StringBuilder embeddingsTxt = null;
            int index = 0;

            // Loop through the possible names and complete the corresponding XML format for the
            // configuration file using the index that matches the given name
            while((embeddingsTxt == null) && (index < embeddingsNames.length))
            {
                if(embeddingsNames[index] == givenName)
                {
                    embeddingsTxt = new StringBuilder("        <word_embeddings field=\"");
                    embeddingsTxt.append(embeddingsFields[index]).append("\">").append(lexicaPath);
                    embeddingsTxt.append(embeddingsFiles[index]).append("</word_embeddings>\r\n");
                }
                index++;
            }

            // If no names matches, the user gave an unknown name, thus return a string mentioning the error
            // which will be handled in the execute method and properly wrapped as an error data object
            // to be returned to the user
            if(embeddingsTxt == null)
            {
                StringBuilder errorMsg = new StringBuilder("EMBEDDINGS ERROR;");
                errorMsg.append(givenName);
                return errorMsg.toString();
            }

            else
            {
                configTxt.append(embeddingsTxt);
            }
        }

        // If no lexica was set, remove the heading added at the beginning
        // If lexica was set, end the lexica section in the XML file
        if(lexicaSet)
        {
            configTxt.append("    </lexica>\r\n\r\n");
        }
        // END OF LEXICA

        // START OF OPTIMIZER
        if(inputData.getParameter("algorithm") != null)
        {
            String givenName = (String) inputData.getParameter("algorithm");

            //ArrayList<String> algorithmNames = new ArrayList<>();
            //algorithmNames.add("perceptron");
            //algorithmNames.add("softmax-regression");
            //algorithmNames.add("adagrad");
            //algorithmNames.add("adagrad-mini-batch");
            //algorithmNames.add("adagrad-regression");
            //algorithmNames.add("adadelta-mini-batch");

            //if(algorithmNames.contains(givenName))

            if(!(givenName.equals("perceptron") || givenName.equals("softmax-regression")
                    || givenName.equals("adagrad") || givenName.equals("adagrad-mini-batch")
                    || givenName.equals("adagrad-regression") || givenName.equals("adadelta-mini-batch")))
            {
                StringBuilder errorMsg = new StringBuilder("ALGORITHM ERROR;");
                errorMsg.append(givenName);
                return errorMsg.toString();
            }

            else
            {
                configTxt.append("    <optimizer>\r\n");
                configTxt.append("        <algorithm>").append(givenName).append("</algorithm>\r\n");
                if(inputData.getParameter("regularization") != null)
                {
                    String givenNumber = (String) inputData.getParameter("regularization");
                    configTxt.append("        <l1_regularization>").append(givenNumber).append("</l1_regularization>\r\n");
                }

                if(inputData.getParameter("rate") != null)
                {
                    String givenNumber = (String) inputData.getParameter("rate");
                    configTxt.append("        <learning_rate>").append(givenNumber).append("</learning_rate>\r\n");
                }

                if(inputData.getParameter("cutoff") != null)
                {
                    String givenNumber = (String) inputData.getParameter("cutoff");
                    configTxt.append("        <feature_cutoff>").append(givenNumber).append("</feature_cutoff>\r\n");
                }

                boolean lolsSet = false;

                if(inputData.getParameter("lols-fixed") != null)
                {
                    lolsSet = true;
                    String givenNumber = (String) inputData.getParameter("lols-fixed");
                    configTxt.append("        <lols fixed=\"").append(givenNumber).append("\"");
                }

                if(inputData.getParameter("lols-decaying") != null)
                {
                    String givenNumber = (String) inputData.getParameter("lols-decaying");

                    if(lolsSet)
                    {
                        configTxt.append(" decaying=\"").append(givenNumber).append("\"/>\r\n");
                    }

                    // This is assuming that one can set decaying without fixed.
                    // TODO: Check if this is possible
                    else
                    {
                        configTxt.append("        <lols decaying=\"").append(givenNumber).append("\"/>\r\n");
                    }
                }

                if(inputData.getParameter("max-epoch") != null)
                {
                    String givenNumber = (String) inputData.getParameter("max-epoch");
                    configTxt.append("        <max_epoch>").append(givenNumber).append("</max_epoch>\r\n");
                }

                if(inputData.getParameter("batch-size") != null)
                {
                    String givenNumber = (String) inputData.getParameter("batch-size");
                    configTxt.append("        <batch_size>").append(givenNumber).append("</batch_size>\r\n");
                }

                if(inputData.getParameter("bias") != null)
                {
                    String givenNumber = (String) inputData.getParameter("bias");
                    configTxt.append("        <bias>").append(givenNumber).append("</bias>\r\n");
                }

                configTxt.append("    </optimizer>\r\n\r\n");
            }
        }
        // END OF OPTIMIZER

        // START OF FEATURES
        // Before starting a counter and examining subsequent feature templates, check if the feature
        // indexed at 0 is specified, if so create the header for all feature templates then check
        // for further indices
        if((inputData.getParameter("1-f0-source") != null)
                && (inputData.getParameter("1-f0-field") != null))
        {

            configTxt.append("    <feature_template>\r\n");

            boolean featureFound = true;
            boolean lineFound = true;

            // Counter holding the index of the current feature.
            int f = 0; // NLP4J starts feature indexing at 0
            // Counter holding the index of the current feature line
            int i = 1; // Starts at 1 because we are checking for features within the first feature line

            // These Strings will hold the user variables
            String source, window, relation, field, value;

            // These Strings will hold the name of the parameter to be accessed
            // They will be changed to look for further features and further lines
            String sourceKey = "1-f0-source";
            String windowKey = "1-f0-window";
            String relationKey = "1-f0-relation";
            String fieldKey = "1-f0-field";
            String valueKey = "1-f0-value";

            // These StringBuilders will be used to create the keys when changing the feature number
            // or line being checked
            StringBuilder sourceBuilder, windowBuilder, relationBuilder, fieldBuilder, valueBuilder;

            while(lineFound)
            {

                while(featureFound)
                {

                    if((inputData.getParameter(sourceKey) != null)
                            && (inputData.getParameter(fieldKey) != null))
                    {
                        featureFound = true;

                        configTxt.append(" ");
                        if(f == 0)
                        {
                            configTxt.append("       <feature ");
                        }

                        source = (String) inputData.getParameter(sourceKey);
                        if(!EnumUtils.isValidEnum(Source.class, source))
                        {
                            StringBuilder errorMsg = new StringBuilder("INVALID FEATURE SOURCE ERROR;");
                            errorMsg.append(source).append(";").append(i).append(";").append(f);
                            return errorMsg.toString();
                        }

                        configTxt.append("f").append(f);
                        configTxt.append("=\"").append(source);

                        if(inputData.getParameter(windowKey) != null)
                        {
                            window = (String) inputData.getParameter(windowKey);
                            if(!((window.contains("-")) || (window.contains("+"))))
                            {
                                configTxt.append("+");
                            }
                            configTxt.append(window);
                        }

                        if(inputData.getParameter(relationKey) != null)
                        {
                            relation = (String) inputData.getParameter(relationKey);
                            if(!EnumUtils.isValidEnum(Relation.class, relation))
                            {
                                StringBuilder errorMsg = new StringBuilder("INVALID FEATURE RELATION ERROR;");
                                errorMsg.append(source).append(";").append(i).append(";").append(f);
                                return errorMsg.toString();
                            }
                            configTxt.append("_").append(relation);
                        }

                        field = (String) inputData.getParameter(fieldKey);
                        if(!EnumUtils.isValidEnum(Field.class, field))
                        {
                            StringBuilder errorMsg = new StringBuilder("INVALID FEATURE FIELD ERROR;");
                            errorMsg.append(source).append(";").append(i).append(";").append(f);
                            return errorMsg.toString();
                        }
                        configTxt.append(":").append(field);

                        if(inputData.getParameter(valueKey) != null)
                        {
                            value = (String) inputData.getParameter(valueKey);
                            configTxt.append(":").append(value);
                        }

                        configTxt.append("\"");

                        f++;

                    }

                    else
                    {
                        // If f is not 0, then there has been at least one feature on this line, which
                        // means this line wasn't empty
                        if(f != 0)
                        {
                            lineFound = true;
                            featureFound = true;
                            configTxt.append("/>\r\n");
                            f = 0;
                            i++;
                        }

                        else
                        {
                            featureFound = false;
                            lineFound = false;
                        }
                    }

                    sourceBuilder = new StringBuilder();
                    sourceBuilder.append(i).append("-f").append(f).append("-source");
                    sourceKey = sourceBuilder.toString();

                    fieldBuilder = new StringBuilder();
                    fieldBuilder.append(i).append("-f").append(f).append("-field");
                    fieldKey = fieldBuilder.toString();

                    windowBuilder = new StringBuilder();
                    windowBuilder.append(i).append("-f").append(f).append("-window");
                    windowKey = windowBuilder.toString();

                    relationBuilder = new StringBuilder();
                    relationBuilder.append(i).append("-f").append(f).append("-relation");
                    relationKey = relationBuilder.toString();

                    valueBuilder = new StringBuilder();
                    valueBuilder.append(i).append("-f").append(f).append("-value");
                    valueKey = valueBuilder.toString();
                }
            }
            configTxt.append("    </feature_template>\r\n\r\n");
        }
        // END OF FEATURES


        configTxt.append("</configuration>");

        Path filePath = writeTempFile("config", dir, configTxt.toString(), ".xml");

        return filePath.toString();
    }


    /** This method takes an error message and returns it in a {@code Data}
     * object with the discriminator set to http://vocab.lappsgrid.org/ns/error
     *
     * @param message A string representing the error message
     * @return A JSON string containing a Data object with the message as a payload.
     */
    private String generateError(String message)
    {
        Data<String> data = new Data<>();
        data.setDiscriminator(Discriminators.Uri.ERROR);
        data.setPayload(message);
        return data.asPrettyJson();
    }


    /** This method will read a text file from a path, and output its contents as a String.
     *
     * @param path The path to the text file that should be read
     * @return A String representing the contents of the text file.
     */
    public String readFile(String path) throws IOException
    {
        StringBuilder output = new StringBuilder();
        BufferedReader br = new BufferedReader(new FileReader(path));
        String line = br.readLine();
        while (line != null) {
            output.append(line).append("\r\n");
            line = br.readLine();
        }
        br.close();
        return output.toString();
    }

    /** This method creates a temporary text file at a certain directory, and writes
     * the given content into the file. The file will also be set to delete on exit.
     *
     * @param fileName The prefix for the temporary file to be created
     * @param dirPath The path to the directory in which the file should be created
     * @param fileTxt The text to be written in the file
     * @param extension The extension to be given to the temporary file
     * @return A path to the temporary text file that was created
     */
    public Path writeTempFile(String fileName, Path dirPath, String fileTxt, String extension) throws IOException
    {
        Path filePath = Files.createTempFile(dirPath, fileName, extension);
        File file = filePath.toFile();
        PrintWriter writer = new PrintWriter(file, "UTF-8");
        writer.print(fileTxt);
        writer.close();
        file.deleteOnExit();
        return filePath;
    }

}

//INCOMPLETE
/** This method will read a XZ file from a path, and output its contents as a String.
 *
 * @param path The path to the text file that should be read
 * @return A String representing the contents of the text file.
 */
 /*
    public String readXZFile(String path) throws IOException {
        FileInputStream infile = new FileInputStream(path);
        BufferedInputStream buffin = new BufferedInputStream(infile);
        XZInputStream inxz = new XZInputStream(buffin);
        int n = 0;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (-1 != (n = inxz.read(buffer))) {
            baos.write(buffer, 0, n);
        }
        return baos.toString();
    }
*/

