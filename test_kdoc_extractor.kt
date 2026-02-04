package afield.test

import afield.ai.KDocExtractor
import java.nio.file.Paths
import kotlin.io.path.readText

fun main() {
    val testFile = Paths.get("test_kdoc_sample.kt")
    val sourceText = testFile.readText()
    
    println("=== Testing KDoc Extraction ===")
    println()
    
    val kdocs = KDocExtractor.extractKDocs(sourceText)
    
    println("Found ${kdocs.size} KDoc comments:")
    println()
    
    kdocs.forEach { kdoc ->
        println("Declaration: ${kdoc.declarationType} ${kdoc.declarationName} (line ${kdoc.declarationLine})")
        println("KDoc (lines ${kdoc.startLine}-${kdoc.endLine}):")
        println("---")
        println(kdoc.text)
        println("---")
        println()
    }
    
    println("=== Testing KDoc Index ===")
    val index = KDocExtractor.buildKDocIndex(sourceText)
    println("Index contains ${index.size} entries:")
    index.forEach { (name, doc) ->
        println("$name: ${doc.take(50)}...")
    }
}
