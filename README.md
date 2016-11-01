# org.lappsgrid.nlp4j-train
The Train command from [EmoryNLP's NLP4J](https://emorynlp.github.io/nlp4j/)

## Usage

Takes a Data<String> object with a [Uri.GET](http://vocab.lappsgrid.org/ns/action/get) discriminator and a String payload representing the json format of a HashMap<String,String> containing all needed input. Additional program-specific parameters can be added following the list below.

## Input keys

These are the valid input keys to be used in the payload HashMap.

| Key | Content |
| train[k] | content of train files in TSV format. Use one key per file, with incrementally numbered keys. Eg: train1, train2, train3, etc... |
| develop[k] | content of development set files, in TSV format. Use one key per file, with incrementally numbered keys. Eg: develop1, develop2, develop3, etc... |

## General Parameters

These are general parameters that can be set for the train function. All parameters take Strings. All these general parameters are OPTIONAL.

| Parameter | Description |
| --- | --- |
| mode | NLP Component to be trained. Valid options can be found [here](md/parameters/mode.md) |
| cv | Number of cross-validation folds. If this number exceeds 1, cross-validation will be performed on training data |
| saveModel | Name of model to be saved. Default = don't save |

## Configuration Parameters

These are the parameters used to create an appropriate configuration file, needed to run the NLP4J Train function. Examples correspond to the configuration example file found here [md/examples/config-example.xml]. Unless otherwise indicated, all parameters are OPTIONAL.

### TSV Parameters

These parameters specify the configuration to be used in reading the TSV files.

| Parameter | Description | Example |
| --- | --- |
| tsv-fields | (REQUIRED) A list of fields, corresponding to the field in each column of the input TSV file | "form, lemma, pos, feats, dheap, deprel" |
| tsv-indices | A list of indices, corresponding to the column indices of the fields mentioned in the tsv-fields parameter. This can be used if the given TSV file has columns not corresponding to the fields mentioned above, or if the user wants to ignore some columns. Default = starting at 0 | "1,2,3,4,5,6" (result in ignoring column 0) |

### Lexica Parameters

These parameters specify the lexica to use when accomplishing different tasks. They use pre-included lexica files, which can be found [here](src/main/resources/lexica).

| Parameter | Description | Valid Values |
| ambiguity | Field for ambiguity classes used for part-of-speech tagging | [Ambiguity Values](md/parameters/ambiguity.md) |
| clusters | Field for word clusters | [Clusters Values](md/parameters/clusters.md) |
| gazetteers | Field for gazetteers used for named entity recognition | [Gazetteers Values](md/parameters/gazetteers.md) |
| embeddings | Field for word embeddings | [Embeddings Values](md/parameters/embeddings.md) |

### Optimizer Parameters

These parameters specify the optimizer used in order to train the statistical model.

| Parameter | Description |
| --- | --- |
| algorithm | Name of the algorithm to be used. Algorithm must be set for the optimizer to be used. Valid algorithm names are: perceptron, softmax-regression, adagrad, adagrad-mini-batch, adagrad-regression, adadelta-mini-batch |
| regularization | The regularized dual averaging (RDA) regularization parameter for adagrad algorithms |
| rate | The learning rate |
| cutoff | The feature cutoff. Features appearing less than or equal to this value will be discarded. |
| lols-fixed | Locally optimal learning to search, using only gold labels for the specified number of epochs |
| lols-decaying | Locally optimal learning to search, decaying the use of gold labels by the specified rate for every epoch |
| max-epoch | The maximum number of epochs to be used in training |
| batch-size | The number of sentences used to train with mini-batch |
| bias | the bias value |

### Feature Template Parameters

These parameters specify the features used during training. Each index [x] represents a feature line, and each index [y] represents the number of the feature on the line. [x] starts at 1 (the first feature line), while [y] starts at 0 (the feature index used by NLP4J). Although all these parameters are optional overall, some specific fields are minimally required for a valid feature line.

| Parameter Key | Description | Valid Values | Starting Key |
| --- | --- | --- |
| [x]-f[y]-source | (Required) Source from which to start | [Source Values](md/parameters/f-source.md) | 1-f0-source |
| [x]-f[y]-window | The context window with respect to the source | ±Value | 1-f0-window |
| [x]-f[y]-relation | The relation to the source | [Relation Values](md/parameters/f-relation.md) | 1-f0-relation |
| [x]-f[y]-field | (Required) Field type | [Field Values](md/paraemters/f-field.md) | 1-f0-field |
| [x]-f[y]-value | The extra value of the field | Value | 1-f0-value |
