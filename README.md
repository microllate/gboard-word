# Gboard Word

A small LSPosed module for a local personal-learning layer on Gboard Chinese Pinyin.

## Current stage

Version 0.1.0 only observes Gboard's Chinese candidate-selection entry point. It does not modify candidates, Gboard's dictionary, or input behavior.

Target package: `com.google.android.inputmethod.latin`

Hook: `AbstractHmmChineseDecodeProcessor.Z(oog, boolean)`

The first observation records the selected candidate text and Gboard's candidate index to logcat. Later stages will add HMM token extraction, local learning storage, context scoring, and candidate reranking.
