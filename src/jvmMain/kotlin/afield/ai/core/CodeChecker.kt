package afield.ai.core

import afield.KotlinInterface
import afield.clojure.ClojureHost
import afield.clojure.CompileOnlyResult
import afield.four.modules.ShaderKind
import afield.four.modules.ShaderSPIRVUtils
import afield.logger.lprintln
import kotlinx.coroutines.runBlocking
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic

/**
 * CodeChecker provides compilation/validation checking for multiple languages
 * without executing the code.
 */
object CodeChecker {
    
    /**
     * Check Kotlin code for compilation errors
     */
    fun checkKotlin(code: String, kotlinInterface: KotlinInterface): CheckCodeResult {
        return try {
            val result = runBlocking {
                kotlinInterface.compile(code)
            }
            
            when (result) {
                is ResultWithDiagnostics.Success -> {
                    CheckCodeResult(success = true)
                }
                is ResultWithDiagnostics.Failure -> {
                    val errors = result.reports.mapNotNull { diagnostic ->
                        if (diagnostic.severity == ScriptDiagnostic.Severity.ERROR ||
                            diagnostic.severity == ScriptDiagnostic.Severity.FATAL) {
                            val location = diagnostic.location
                            CodeError(
                                line = location?.start?.line,
                                column = location?.start?.col,
                                message = diagnostic.message,
                                severity = diagnostic.severity.name.lowercase()
                            )
                        } else null
                    }
                    CheckCodeResult(success = false, errors = errors)
                }
            }
        } catch (e: Exception) {
            lprintln("error,code_checker", "Kotlin compilation check failed: ${e.message}")
            CheckCodeResult(
                success = false,
                errors = listOf(
                    CodeError(
                        message = "Compilation check failed: ${e.message}",
                        severity = "error"
                    )
                )
            )
        }
    }
    
    /**
     * Check Python code for syntax errors using py_compile via Jep
     */
    fun checkPython(code: String): CheckCodeResult {
        return try {
            // Check if Python/Jep is initialized
            if (!afield.python.PythonInterface.inited) {
                lprintln("warning,code_checker", "Python/Jep not initialized, attempting to initialize...")
                try {
                    afield.python.PythonExecution().interpreter(null)
                } catch (e: Exception) {
                    return CheckCodeResult(
                        success = false,
                        errors = listOf(
                            CodeError(
                                message = "Python interpreter not available: ${e.message}",
                                severity = "error"
                            )
                        )
                    )
                }
            }
            
            // Use Jep's SharedInterpreter to run py_compile
            val errors = mutableListOf<CodeError>()
            var success = true
            
            jep.SharedInterpreter().use { interp ->
                try {
                    // Set the code as a variable in Python
                    interp.set("_pycheck_code", code)
                    
                    // Use compile() built-in to check syntax (simpler than py_compile)
                    val checkScript = """
import sys
from io import StringIO

_pycheck_output = StringIO()
sys.stdout = _pycheck_output

try:
    compile(_pycheck_code, '<string>', 'exec')
    print('PYCHECK_SUCCESS')
except SyntaxError as e:
    print(f'PYCHECK_ERROR|{e.lineno or ""}|{e.offset or ""}|{e.msg}')
except Exception as e:
    print(f'PYCHECK_ERROR|||{str(e)}')
finally:
    sys.stdout = sys.__stdout__
"""
                    
                    // Execute the check script
                    interp.exec(checkScript)
                    
                    // Get the output
                    val result = interp.getValue("_pycheck_output.getvalue()") as String
                    
                    // Parse the result
                    if (result.contains("PYCHECK_SUCCESS")) {
                        success = true
                    } else if (result.contains("PYCHECK_ERROR")) {
                        success = false
                        val errorLine = result.lines().firstOrNull { it.startsWith("PYCHECK_ERROR") }
                        if (errorLine != null) {
                            val parts = errorLine.split("|")
                            if (parts.size >= 4) {
                                val line = parts[1].toIntOrNull()
                                val column = parts[2].toIntOrNull()
                                val message = parts.drop(3).joinToString("|")
                                errors.add(
                                    CodeError(
                                        line = line,
                                        column = column,
                                        message = message,
                                        severity = "error"
                                    )
                                )
                            } else {
                                errors.add(
                                    CodeError(
                                        message = errorLine.substringAfter("PYCHECK_ERROR|"),
                                        severity = "error"
                                    )
                                )
                            }
                        }
                    }
                } catch (je: jep.JepException) {
                    success = false
                    // Parse Jep exception for Python errors
                    val message = je.message ?: "Unknown Python error"
                    
                    // Try to extract line number from exception message
                    val linePattern = Regex("""line (\d+)""")
                    val lineMatch = linePattern.find(message)
                    val line = lineMatch?.groupValues?.get(1)?.toIntOrNull()
                    
                    errors.add(
                        CodeError(
                            line = line,
                            message = message,
                            severity = "error"
                        )
                    )
                }
            }
            
            CheckCodeResult(success = success, errors = errors)
            
        } catch (e: Exception) {
            lprintln("error,code_checker", "Python syntax check failed: ${e.message}")
            CheckCodeResult(
                success = false,
                errors = listOf(
                    CodeError(
                        message = "Syntax check failed: ${e.message}",
                        severity = "error"
                    )
                )
            )
        }
    }
    
    /**
     * Check Clojure code for compilation errors
     */
    fun checkClojure(code: String, clojureHost: ClojureHost): CheckCodeResult {
        return try {
            when (val result = clojureHost.compileOnly(code)) {
                is CompileOnlyResult.Ok -> {
                    CheckCodeResult(success = true)
                }
                is CompileOnlyResult.Err -> {
                    val errors = result.errors.map { error ->
                        CodeError(
                            line = error.line,
                            column = error.column,
                            message = error.message,
                            severity = "error"
                        )
                    }
                    CheckCodeResult(success = false, errors = errors)
                }
            }
        } catch (e: Exception) {
            lprintln("error,code_checker", "Clojure compilation check failed: ${e.message}")
            CheckCodeResult(
                success = false,
                errors = listOf(
                    CodeError(
                        message = "Compilation check failed: ${e.message}",
                        severity = "error"
                    )
                )
            )
        }
    }
    
    /**
     * Check GLSL shader code for compilation errors
     */
    fun checkGLSL(code: String, property: String? = null): CheckCodeResult {
        return try {
            // Determine shader kind from property name
            val shaderKind = when (property?.lowercase()) {
                "vertex" -> ShaderKind.VERTEX_SHADER
                "fragment" -> ShaderKind.FRAGMENT_SHADER
                "compute" -> ShaderKind.COMPUTE_SHADER
                "mesh" -> ShaderKind.MESH_SHADER
                "task" -> ShaderKind.TASK_SHADER
                "geometry" -> ShaderKind.GEOMETRY_SHADER
                else -> ShaderKind.FRAGMENT_SHADER // Default to fragment shader
            }
            
            // Try to compile the shader
            val spirv = ShaderSPIRVUtils.compileShader(
                filename = "<inline>",
                source = code,
                shaderKind = shaderKind
            )
            
            // Clean up
            spirv.free()
            
            CheckCodeResult(success = true)
        } catch (e: afield.four.modules.CompilerError) {
            // Parse GLSL error message which typically includes line numbers
            val errorMessage = e.message ?: "Unknown compilation error"
            val errors = parseGLSLErrors(errorMessage)
            CheckCodeResult(success = false, errors = errors)
        } catch (e: Exception) {
            lprintln("error,code_checker", "GLSL compilation check failed: ${e.message}")
            CheckCodeResult(
                success = false,
                errors = listOf(
                    CodeError(
                        message = "Compilation check failed: ${e.message}",
                        severity = "error"
                    )
                )
            )
        }
    }
    
    /**
     * Parse GLSL error messages to extract line numbers and error details
     */
    private fun parseGLSLErrors(errorMessage: String): List<CodeError> {
        val errors = mutableListOf<CodeError>()
        
        // GLSL error format: "ERROR: <file>:<line>: <message>"
        // or "ERROR: 0:<line>: <message>"
        val linePattern = Regex("""ERROR:\s*[^:]*:(\d+):\s*(.+)""")
        
        errorMessage.lines().forEach { line ->
            val match = linePattern.find(line)
            if (match != null) {
                val lineNum = match.groupValues[1].toIntOrNull()
                val message = match.groupValues[2].trim()
                errors.add(
                    CodeError(
                        line = lineNum,
                        message = message,
                        severity = "error"
                    )
                )
            } else if (line.contains("ERROR", ignoreCase = true)) {
                // Fallback for errors without line numbers
                errors.add(
                    CodeError(
                        message = line.trim(),
                        severity = "error"
                    )
                )
            }
        }
        
        // If no errors were parsed, return the whole message
        if (errors.isEmpty()) {
            errors.add(
                CodeError(
                    message = errorMessage,
                    severity = "error"
                )
            )
        }
        
        return errors
    }
}
