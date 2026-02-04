#!/usr/bin/env kotlin

import java.io.File

// Simple standalone test for KDoc extraction logic
fun main() {
    val testCode = """
/**
 * This is a test function
 * with multiple lines
 */
fun testFunction(x: Int): Int {
    return x * 2
}

/**
 * A test class
 */
class TestClass {
    /**
     * A property
     */
    val prop: String = "test"
}
    """.trimIndent()
    
    println("=== Testing KDoc Extraction ===")
    println("Test code:")
    println(testCode)
    println()
    
    // Test extraction logic
    val kdocs = extractKDocs(testCode)
    println("Found ${kdocs.size} KDoc comments")
    kdocs.forEach { kdoc ->
        println("- ${kdoc.type} ${kdoc.name} at line ${kdoc.line}: ${kdoc.doc.take(50)}...")
    }
}

data class KDocInfo(val doc: String, val line: Int, val name: String?, val type: String?)

fun extractKDocs(text: String): List<KDocInfo> {
    val results = mutableListOf<KDocInfo>()
    val lines = text.split("\n")
    var i = 0
    
    while (i < lines.size) {
        val line = lines[i].trimStart()
        
        if (line.startsWith("/**")) {
            val docLines = mutableListOf<String>()
            var j = i
            var foundEnd = false
            
            while (j < lines.size) {
                val docLine = lines[j].trimStart()
                
                if (docLine.contains("*/")) {
                    foundEnd = true
                    j++
                    break
                }
                
                if (j > i) {
                    val content = docLine.removePrefix("*").trimStart()
                    if (content.isNotEmpty()) {
                        docLines.add(content)
                    }
                }
                j++
            }
            
            if (foundEnd) {
                // Look for declaration
                while (j < lines.size) {
                    val declLine = lines[j].trimStart()
                    if (declLine.isEmpty() || declLine.startsWith("@")) {
                        j++
                        continue
                    }
                    
                    val decl = parseDeclaration(declLine)
                    if (decl != null) {
                        results.add(KDocInfo(
                            doc = docLines.joinToString("\n"),
                            line = j + 1,
                            name = decl.second,
                            type = decl.first
                        ))
                    }
                    break
                }
                i = j
            } else {
                i = j
            }
        } else {
            i++
        }
    }
    
    return results
}

fun parseDeclaration(line: String): Pair<String, String>? {
    val tokens = line.trim().split(Regex("\\s+"))
    
    for ((idx, token) in tokens.withIndex()) {
        when (token) {
            "fun" -> {
                if (idx + 1 < tokens.size) {
                    val name = tokens[idx + 1].substringBefore("(")
                    return "fun" to name
                }
            }
            "class", "interface", "object" -> {
                if (idx + 1 < tokens.size) {
                    val name = tokens[idx + 1].substringBefore("{").substringBefore("(").trim()
                    return token to name
                }
            }
            "val", "var" -> {
                if (idx + 1 < tokens.size) {
                    val name = tokens[idx + 1].substringBefore(":").substringBefore("=").trim()
                    return token to name
                }
            }
        }
    }
    
    return null
}

main()
