package app.aaps.plugins.aps.openAPSAIMI

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.io.FileNotFoundException
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

class aimiNeuralNetwork(
    private val inputSize: Int,
    private val hiddenSize: Int,
    private val outputSize: Int,
    private var learningRate: Double = 0.01,
    private val regularizationLambda: Double = 0.01,
    private val dropoutRate: Double = 0.5
) {

    private var weightsInputHidden = Array(inputSize) { DoubleArray(hiddenSize) { Random.nextDouble(-sqrt(6.0 / (inputSize + hiddenSize)), sqrt(6.0 / (inputSize + hiddenSize))) } }
    private var biasHidden = DoubleArray(hiddenSize) { 0.01 }
    private var weightsHiddenOutput = Array(hiddenSize) { DoubleArray(outputSize) { Random.nextDouble(-sqrt(6.0 / (hiddenSize + outputSize)), sqrt(6.0 / (hiddenSize + outputSize))) } }
    private var biasOutput = DoubleArray(outputSize) { 0.01 }
    private var modelFilePath: String? = null

    var lastTrainingException: Exception? = null
    var trainingLossHistory: MutableList<Double> = mutableListOf()

    companion object {

        fun refineSMB(smb: Float, neuralNetwork: aimiNeuralNetwork, input: FloatArray): Float {
            val prediction = neuralNetwork.predict(input)[0]
            return smb + prediction.toFloat()
        }

        fun refineBasalaimi(basalaimi: Float, neuralNetwork: aimiNeuralNetwork, input: FloatArray): Float {
            val prediction = neuralNetwork.forwardPassBasal(input).second[0]
            return basalaimi + prediction.toFloat()
        }
    }

    private fun applyDropout(layer: DoubleArray): DoubleArray {
        return layer.map { if (Random.nextDouble() > dropoutRate) it else 0.0 }.toDoubleArray()
    }

    private fun relu(x: Double) = max(0.0, x)

    fun createNewFeature(inputData: List<FloatArray>): List<FloatArray> {
        return inputData.map { originalFeatures ->
            val newFeature = originalFeatures[0] * originalFeatures[1]
            originalFeatures + newFeature
        }
    }

    fun trainForBasalaimi(
        inputs: List<FloatArray>,
        basalaimiTargets: List<DoubleArray>,
        epochs: Int
    ) {
        for (epoch in 1..epochs) {
            var totalLoss = 0.0
            for ((input, target) in inputs.zip(basalaimiTargets)) {
                val (hidden, output) = forwardPassBasal(input)
                totalLoss += mseLoss(output, target)
                backpropagationBasal(input, target)
            }
            val averageLoss = totalLoss / inputs.size
            println("Epoch $epoch: Training Loss = $averageLoss")
            trainingLossHistory.add(averageLoss)
        }
    }

    private fun backpropagationBasal(input: FloatArray, target: DoubleArray) {
        val (hidden, output) = forwardPassBasal(input)
        val gradLossOutput = DoubleArray(outputSize) { i -> 2.0 * (output[i] - target[i]) }

        for (i in 0 until outputSize) {
            for (j in 0 until hiddenSize) {
                weightsHiddenOutput[j][i] -= learningRate * gradLossOutput[i] * hidden[j]
            }
            biasOutput[i] -= learningRate * gradLossOutput[i]
        }

        val gradLossHidden = DoubleArray(hiddenSize) { 0.0 }
        for (i in 0 until hiddenSize) {
            for (j in 0 until outputSize) {
                gradLossHidden[i] += gradLossOutput[j] * weightsHiddenOutput[i][j]
            }
            if (hidden[i] <= 0) {
                gradLossHidden[i] = 0.0
            }
        }

        for (i in 0 until inputSize) {
            for (j in 0 until hiddenSize) {
                weightsInputHidden[i][j] -= learningRate * gradLossHidden[j] * input[i]
            }
        }
    }

    private fun forwardPassBasal(input: FloatArray): Pair<DoubleArray, DoubleArray> {
        val hiddenLayer = DoubleArray(hiddenSize) { i ->
            var sum = 0.0
            for (j in input.indices) {
                sum += input[j] * weightsInputHidden[j][i]
            }
            relu(sum + biasHidden[i])
        }

        val outputLayer = DoubleArray(outputSize) { i ->
            var sum = 0.0
            for (j in hiddenLayer.indices) {
                sum += hiddenLayer[j] * weightsHiddenOutput[j][i]
            }
            sum + biasOutput[i]
        }

        return Pair(hiddenLayer, outputLayer)
    }

    private fun zScoreNormalization(inputData: List<FloatArray>): List<FloatArray> {
        if (inputData.isEmpty()) {
            return emptyList()
        }
        val means = FloatArray(inputData.first().size) { 0f }
        val stdDevs = FloatArray(inputData.first().size) { 0f }

        inputData.forEach { features ->
            features.forEachIndexed { index, value ->
                means[index] += value
                stdDevs[index] += value * value
            }
        }

        means.indices.forEach { i ->
            means[i] /= inputData.size.toFloat()
            stdDevs[i] = sqrt(stdDevs[i] / inputData.size - means[i] * means[i])
        }

        return inputData.map { features ->
            FloatArray(features.size) { index ->
                if (stdDevs[index] != 0.0f) {
                    (features[index] - means[index]) / stdDevs[index]
                } else {
                    features[index] - means[index]
                }
            }
        }
    }

    private fun forwardPass(input: FloatArray): Pair<DoubleArray, DoubleArray> {
        val hidden = DoubleArray(hiddenSize) { i ->
            var sum = 0.0
            for (j in input.indices) {
                sum += input[j] * weightsInputHidden[j][i]
            }
            relu(sum + biasHidden[i])
        }
        val hiddenWithDropout = applyDropout(hidden)

        val hidden2 = DoubleArray(hiddenSize) { i ->
            var sum = 0.0
            for (j in hiddenWithDropout.indices) {
                sum += hiddenWithDropout[j] * weightsInputHidden[j][i]
            }
            relu(sum + biasHidden[i])
        }

        val output = DoubleArray(outputSize) { i ->
            var sum = 0.0
            for (j in hidden2.indices) {
                sum += hidden2[j] * weightsHiddenOutput[j][i]
            }
            sum + biasOutput[i]
        }

        return Pair(hidden2, output)
    }

    fun predict(input: FloatArray): DoubleArray {
        return forwardPass(input).second
    }

    private fun mseLoss(output: DoubleArray, target: DoubleArray): Double {
        return output.indices.sumOf { i -> (output[i] - target[i]).pow(2.0) } / output.size
    }

    private fun l2Regularization(): Double {
        var regularizationLoss = 0.0
        weightsInputHidden.forEach { layer ->
            layer.forEach { weight ->
                regularizationLoss += weight.pow(2.0)
            }
        }
        weightsHiddenOutput.forEach { layer ->
            layer.forEach { weight ->
                regularizationLoss += weight.pow(2.0)
            }
        }
        return regularizationLoss * regularizationLambda
    }

    fun train(
        inputs: List<FloatArray>,
        targets: List<DoubleArray>,
        validationRawInputs: List<FloatArray>,
        validationTargets: List<DoubleArray>,
        epochs: Int,
        batchSize: Int = 32,
        patience: Int = 10
    ) {
        var bestLoss = Double.MAX_VALUE
        var epochsWithoutImprovement = 0
        val preparedInputs = createNewFeature(inputs)
        val normalizedInputs = zScoreNormalization(preparedInputs)
        val preparedValidationInputs = createNewFeature(validationRawInputs)
        val normalizedValidationInputs = zScoreNormalization(preparedValidationInputs)

        var m = Array(weightsInputHidden.size) { DoubleArray(weightsInputHidden[0].size) { 0.0 } }
        var v = Array(weightsInputHidden.size) { DoubleArray(weightsInputHidden[0].size) { 0.0 } }
        var mOutput = Array(weightsHiddenOutput.size) { DoubleArray(weightsHiddenOutput[0].size) { 0.0 } }
        var vOutput = Array(weightsHiddenOutput.size) { DoubleArray(weightsHiddenOutput[0].size) { 0.0 } }
        var t = 1

        for (epoch in 1..epochs.toInt()) {
            var totalLoss = 0.0
            try {
                normalizedInputs.chunked(batchSize.toInt()).zip(targets.chunked(batchSize.toInt())).forEach { (batchInputs, batchTargets) ->
                    batchInputs.zip(batchTargets).forEach { (input, target) ->
                        val output = forwardPass(input).second
                        totalLoss += mseLoss(output, target) + l2Regularization()
                        backpropagation(input, target, m, v, mOutput, vOutput)
                    }
                }
                trainForBasalaimi(normalizedInputs, targets, epochs.toInt())
                trainWithAdam(normalizedInputs, targets, normalizedValidationInputs, validationTargets, epochs - epoch + 1)
                val averageLoss = totalLoss / normalizedInputs.size
                trainingLossHistory.add(averageLoss)

                val validationLoss = validate(normalizedValidationInputs, validationTargets)
                println("Epoch $epoch, Training Loss: $averageLoss, Validation Loss: $validationLoss")

                if (validationLoss < bestLoss) {
                    bestLoss = validationLoss
                    epochsWithoutImprovement = 0
                } else {
                    epochsWithoutImprovement++
                    if (epochsWithoutImprovement >= patience) {
                        println("Early stopping triggered at epoch $epoch")
                        break
                    }
                }

                learningRate *= 0.95 // Réduction dynamique du taux d'apprentissage
                t++
            } catch (e: Exception) {
                lastTrainingException = e
                println("Exception during training at epoch $epoch: ${e.message}")
                break
            }
        }
        saveModel()
    }
    private fun backpropagation(
        input: FloatArray,
        target: DoubleArray,
        m: Array<DoubleArray>,
        v: Array<DoubleArray>,
        mOutput: Array<DoubleArray>,
        vOutput: Array<DoubleArray>
    ) {
        val (hidden, output) = forwardPass(input)
        val gradLossOutput = DoubleArray(outputSize) { i -> 2.0 * (output[i] - target[i]) }
        val gradHiddenOutput = Array(hiddenSize) { DoubleArray(outputSize) { 0.0 } }
        val gradInputHidden = Array(inputSize) { DoubleArray(hiddenSize) { 0.0 } }

        // Mise à jour des poids de la couche de sortie avec Adam
        for (i in 0 until outputSize) {
            for (j in 0 until hiddenSize) {
                gradHiddenOutput[j][i] = gradLossOutput[i] * hidden[j]
            }
        }
        Adam(weightsHiddenOutput, gradHiddenOutput, mOutput, vOutput, 1, learningRate)

        // Mise à jour des biais de la couche de sortie
        for (i in 0 until outputSize) {
            biasOutput[i] -= learningRate * gradLossOutput[i]
        }

        // Calcul des gradients pour la couche cachée
        val gradLossHidden = DoubleArray(hiddenSize) { j ->
            var sum = 0.0
            for (k in 0 until outputSize) {
                sum += gradLossOutput[k] * weightsHiddenOutput[j][k]
            }
            if (hidden[j] <= 0) 0.0 else sum
        }

        // Mise à jour des poids de la couche cachée avec Adam
        for (i in 0 until inputSize) {
            for (j in 0 until hiddenSize) {
                val gradRelu = if (hidden[j] > 0) 1 else 0.001
                gradInputHidden[i][j] = gradLossHidden[j] * input[i] * gradRelu.toDouble()
            }
        }
        Adam(weightsInputHidden, gradInputHidden, m, v, 1, learningRate)

        // Mise à jour des biais de la couche cachée
        for (j in 0 until hiddenSize) {
            val gradRelu = if (hidden[j] > 0) 1 else 0.001
            biasHidden[j] -= learningRate * gradLossHidden[j] * gradRelu.toDouble()
        }

    }

    private fun Adam(
        params: Array<DoubleArray>,
        grads: Array<DoubleArray>,
        m: Array<DoubleArray>,
        v: Array<DoubleArray>,
        t: Int,
        learningRate: Double = 0.001,
        beta1: Double = 0.9,
        beta2: Double = 0.999,
        epsilon: Double = 1e-8
    ) {
        for (i in params.indices) {
            for (j in params[i].indices) {
                m[i][j] = beta1 * m[i][j] + (1 - beta1) * grads[i][j]
                v[i][j] = beta2 * v[i][j] + (1 - beta2) * grads[i][j] * grads[i][j]
                val mHat = m[i][j] / (1 - beta1.pow(t))
                val vHat = v[i][j] / (1 - beta2.pow(t))
                params[i][j] -= learningRate * mHat / (sqrt(vHat) + epsilon)
            }
        }
    }

    fun saveModel() {
        val modelData = mapOf(
            "weightsInputHidden" to weightsInputHidden,
            "biasHidden" to biasHidden,
            "weightsHiddenOutput" to weightsHiddenOutput,
            "biasOutput" to biasOutput
        )
        val gson = Gson()
        val json = gson.toJson(modelData)

        val directory = File("AAPS/")
        if (!directory.exists()) {
            directory.mkdirs() // Crée le dossier s'il n'existe pas
        }

        val file = File(directory, "model.json")
        file.writeText(json)
        modelFilePath = file.absolutePath
    }

    fun predictWithLoadedModel(input: FloatArray): DoubleArray {
        loadModel()
        return predict(input)
    }
    fun loadModel() {
        try {
            val filePath = "AAPS/model.json" // Chemin d'accès au fichier model.json
            val file = File(filePath)

            if (!file.exists()) {
                throw FileNotFoundException("Le fichier model.json n'a pas été trouvé à l'emplacement $filePath")
            }

            val modelData = Gson().fromJson(file.readText(), Map::class.java)
            weightsInputHidden = modelData["weightsInputHidden"] as Array<DoubleArray>
            biasHidden = modelData["biasHidden"] as DoubleArray
            weightsHiddenOutput = modelData["weightsHiddenOutput"] as Array<DoubleArray>
            biasOutput = modelData["biasOutput"] as DoubleArray
            modelFilePath = filePath
        } catch (e: Exception) {
            // Gestion des erreurs si le fichier n'existe pas ou si une autre erreur survient
            e.printStackTrace()
            // Vous pouvez également gérer l'erreur de manière plus spécifique en fonction de votre application
        }
    }

    fun trainWithAdam(
        inputs: List<FloatArray>,
        targets: List<DoubleArray>,
        validationRawInputs: List<FloatArray>,
        validationTargets: List<DoubleArray>,
        epochs: Int,
        batchSize: Int = 32,
        patience: Int = 10
    ) {
        val preparedInputs = createNewFeature(inputs)
        val normalizedInputs = zScoreNormalization(preparedInputs)
        val preparedValidationInputs = createNewFeature(validationRawInputs)
        val normalizedValidationInputs = zScoreNormalization(preparedValidationInputs)

        var m = Array(weightsInputHidden.size) { DoubleArray(weightsInputHidden[0].size) { 0.0 } }
        var v = Array(weightsInputHidden.size) { DoubleArray(weightsInputHidden[0].size) { 0.0 } }
        var mOutput = Array(weightsHiddenOutput.size) { DoubleArray(weightsHiddenOutput[0].size) { 0.0 } }
        var vOutput = Array(weightsHiddenOutput.size) { DoubleArray(weightsHiddenOutput[0].size) { 0.0 } }
        var t = 1

        var bestLoss = Double.MAX_VALUE
        var epochsWithoutImprovement = 0

        for (epoch in 1..epochs.toInt()) {
            var totalLoss = 0.0
            try {
                normalizedInputs.chunked(batchSize).zip(targets.chunked(batchSize)).forEach { (batchInputs, batchTargets) ->
                    batchInputs.zip(batchTargets).forEach { (input, target) ->
                        val (_, output) = forwardPass(input)
                        val gradOutputs = DoubleArray(outputSize) { i -> 2.0 * (output[i] - target[i]) }
                        val gradInputHidden = backpropagationWithAdam(input, gradOutputs)

                        totalLoss += mseLoss(output, target) + l2Regularization()
                    }
                }

                val averageLoss = totalLoss / normalizedInputs.size
                trainingLossHistory.add(averageLoss)

                val validationLoss = validate(normalizedValidationInputs, validationTargets)
                println("Epoch $epoch, Training Loss: $averageLoss, Validation Loss: $validationLoss")

                if (validationLoss < bestLoss) {
                    bestLoss = validationLoss
                    epochsWithoutImprovement = 0
                } else {
                    epochsWithoutImprovement++
                    if (epochsWithoutImprovement >= patience) {
                        println("Early stopping triggered at epoch $epoch")
                        break
                    }
                }

                learningRate *= 0.95 // Réduction dynamique du taux d'apprentissage
                t++
            } catch (e: Exception) {
                lastTrainingException = e
                println("Exception during training at epoch $epoch: ${e.message}")
                break
            }
        }
    }

    private fun backpropagationWithAdam(input: FloatArray, gradOutputs: DoubleArray): Array<DoubleArray> {
        val (hidden, _) = forwardPass(input)
        val gradInputHidden = Array(inputSize) { DoubleArray(hiddenSize) { 0.0 } }
        val gradHiddenOutput = Array(hiddenSize) { DoubleArray(outputSize) { 0.0 } }

        for (i in 0 until outputSize) {
            for (j in 0 until hiddenSize) {
                gradHiddenOutput[j][i] = gradOutputs[i] * hidden[j]
            }
        }

        for (i in 0 until inputSize) {
            for (j in 0 until hiddenSize) {
                val gradRelu = if (hidden[j] > 0) 1 else 0.001
                var gradLossHidden = 0.0
                for (k in 0 until outputSize) {
                    gradLossHidden += gradOutputs[k] * weightsHiddenOutput[j][k]
                }
                gradLossHidden *= gradRelu.toDouble()
                gradInputHidden[i][j] = gradLossHidden * input[i]
            }
        }

        return gradInputHidden
    }

    private fun validate(inputs: List<FloatArray>, targets: List<DoubleArray>): Double {
        var totalLoss = 0.0
        for ((input, target) in inputs.zip(targets)) {
            val output = forwardPass(input).second
            totalLoss += mseLoss(output, target) + l2Regularization()
        }
        return totalLoss / inputs.size
    }

}
