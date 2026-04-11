package com.whispertflite.sherpa

import com.k2fsa.sherpa.onnx.OnlineModelConfig
import java.io.File

/**
 * Copy every relative path on [OnlineModelConfig] (and nested configs) to absolute paths under [modelsRoot],
 * matching sherpa-onnx `getModelConfig` layout (`dir/file.onnx`, …).
 */
fun absolutizeModelConfig(rel: OnlineModelConfig, modelsRoot: File): OnlineModelConfig {
    fun ap(path: String): String = if (path.isEmpty()) path else File(modelsRoot, path).absolutePath

    rel.transducer.encoder = ap(rel.transducer.encoder)
    rel.transducer.decoder = ap(rel.transducer.decoder)
    rel.transducer.joiner = ap(rel.transducer.joiner)

    rel.paraformer.encoder = ap(rel.paraformer.encoder)
    rel.paraformer.decoder = ap(rel.paraformer.decoder)

    rel.zipformer2Ctc.model = ap(rel.zipformer2Ctc.model)
    rel.neMoCtc.model = ap(rel.neMoCtc.model)
    rel.toneCtc.model = ap(rel.toneCtc.model)

    rel.tokens = ap(rel.tokens)
    rel.bpeVocab = ap(rel.bpeVocab)
    return rel
}

/** Relative path strings required on disk (non-empty fields in a template [OnlineModelConfig]). */
fun collectRelativeModelPaths(cfg: OnlineModelConfig): List<String> {
    val out = ArrayList<String>()
    with(cfg.transducer) {
        if (encoder.isNotEmpty()) out.add(encoder)
        if (decoder.isNotEmpty()) out.add(decoder)
        if (joiner.isNotEmpty()) out.add(joiner)
    }
    with(cfg.paraformer) {
        if (encoder.isNotEmpty()) out.add(encoder)
        if (decoder.isNotEmpty()) out.add(decoder)
    }
    if (cfg.zipformer2Ctc.model.isNotEmpty()) out.add(cfg.zipformer2Ctc.model)
    if (cfg.neMoCtc.model.isNotEmpty()) out.add(cfg.neMoCtc.model)
    if (cfg.toneCtc.model.isNotEmpty()) out.add(cfg.toneCtc.model)
    if (cfg.tokens.isNotEmpty()) out.add(cfg.tokens)
    if (cfg.bpeVocab.isNotEmpty()) out.add(cfg.bpeVocab)
    return out
}
