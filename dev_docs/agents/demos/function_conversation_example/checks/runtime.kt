// Run through Field's backdoor in a disposable box. Reads the saved, model-authored
// sources and compiles their actual Functions.Fn values. Never edits the live study.
import afield.stdlib.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.nio.file.Files
import kotlin.math.*

val root = File("dev_docs/agents/demos/function_conversation_example").canonicalFile
val mapper = jacksonObjectMapper()
val results = linkedMapOf<String, Any?>()
fun revisionFor(turn: String): String = mapper.readTree(File(root,"conversations/$turn.json"))["after"]["result"]["revision"].asText()
fun sourceFor(turn: String): String = File(root,"revisions/${revisionFor(turn)}.kt").readText()
val turns = listOf("01-create", "02-move", "03-stretch", "04-lag", "05-delete", "06-warp", "07-restore-background")
val sources = turns.associateWith(::sourceFor)
val compositions = sources.mapValues { FunctionStudy.compileStudy(it.value) }
val created = compositions.getValue("01-create")
val moved = compositions.getValue("02-move")
val stretched = compositions.getValue("03-stretch")
val lagged = compositions.getValue("04-lag")
val deleted = compositions.getValue("05-delete")
val warped = compositions.getValue("06-warp")
val restored = compositions.getValue("07-restore-background")
fun event(c:StudyComposition,id:String)=c.events.single { it.id==id }
fun maxDifference(a:StudyComposition,b:StudyComposition,start:Double=0.0,end:Double=3600.0,
                  channels:List<Int> = listOf(0,1,2), exclude:Pair<Double,Double>?=null):Double {
    val n=ceil((end-start)/0.011).toInt()
    var worst=0.0
    for(i in 0..n) {
        val t=start+(end-start)*i/n
        if(exclude!=null && t>=exclude.first && t<=exclude.second) continue
        for(ch in channels) worst=max(worst,abs(a.channels[ch](t)-b.channels[ch](t)))
    }
    return worst
}
check(created.duration==3600.0 && created.channels.size==3 && created.events.size==6)
check(created.events.all { it.hold==30.0 })
check(created.events.zipWithNext().all { (a,b) -> b.start-a.start==600.0 })
results["creation"]="3600 seconds, 3 channels, 6 evenly spaced events, 30-second holds"
check(event(moved,"e3")==event(created,"e3").copy(start=event(created,"e3").start+40))
check(moved.events.filter { it.id!="e3" }==created.events.filter { it.id!="e3" })
results["movePreservedMaxAbs"]=maxDifference(created,moved,exclude=event(created,"e3").start to event(moved,"e3").end).also { check(it==0.0) }
check(event(stretched,"e3")==event(moved,"e3").copy(rise=200.0))
results["stretchPreservedMaxAbs"]=maxDifference(moved,stretched,exclude=event(moved,"e3").start to event(stretched,"e3").end).also { check(it==0.0) }
check(lagged.events==stretched.events)
results["lagXZWholeHourMaxAbs"]=maxDifference(stretched,lagged,channels=listOf(0,2)).also { check(it==0.0) }
results["lagYOutsideEventMaxAbs"]=maxDifference(stretched,lagged,channels=listOf(1),exclude=1425.0 to 1755.0).also { check(it==0.0) }
check(maxDifference(stretched,lagged,start=1480.0,end=1540.0,channels=listOf(1))>0.01)
check(deleted.events==lagged.events.filter { it.id!="e4" })
results["deletionPreservedMaxAbs"]=maxDifference(lagged,deleted,exclude=1985.0 to 2215.0).also { check(it==0.0) }
// Expose the actual continuing background by removing event instances from a COPY
// of the model's original source. No hand-written substitute for its background.
val eventDeclaration=Regex("val events = listOf\\([\\s\\S]*?\\n    \\)")
check(eventDeclaration.findAll(sources.getValue("01-create")).count()==1)
val backgroundSource=eventDeclaration.replaceFirst(sources.getValue("01-create"),"val events = emptyList<StudyEvent>()")
val background=FunctionStudy.compileStudy(backgroundSource)
results["deletedRegionVsActualBackgroundMaxAbs"]=maxDifference(background,deleted,start=1985.0,end=2215.0).also { check(it==0.0) }
check(warped.events==deleted.events)
results["warpPreservedMaxAbs"]=maxDifference(deleted,warped,exclude=1100.0 to 1250.0).also { check(it==0.0) }
check(maxDifference(deleted,warped,start=1100.0,end=1250.0)>0.01)
// Observe the source's actual clock variable, in the event-free warp interval.
val clockLine="val base = background(backgroundTime, index)"
val warpSource=sources.getValue("06-warp")
check(warpSource.windowed(clockLine.length).count { it==clockLine }==1)
val clock=FunctionStudy.compileStudy(warpSource.replace(clockLine,"val base = backgroundTime")).channels[0]
check(clock(1100.0)==1100.0 && clock(1250.0)==1250.0)
var minRate=Double.POSITIVE_INFINITY; var maxRate=Double.NEGATIVE_INFINITY
for(i in 0..15000) {
    val t=1100.0+i*.01; val h=.0001
    val rate=(clock(t+h)-clock(t-h))/(2*h)
    minRate=min(minRate,rate);maxRate=max(maxRate,rate)
}
check(minRate>0.0 && abs(minRate-.8626)<.0001 && abs(maxRate-1.1374)<.0001)
results["sampledWarpClockRateBounds"]=listOf(minRate,maxRate)
results["warpEndpointRates"]=listOf(1100.0,1250.0).map { t -> (clock(t+.0001)-clock(t-.0001))/.0002 }.also { check(it.all { abs(it-1)<1e-7 }) }
check(restored.events==deleted.events)
results["restoredVsPreWarpWholeHourMaxAbs"]=maxDifference(restored,deleted).also { check(it==0.0) }
results["allRevisionHoldMaxErrors"]=compositions.mapValues { (_,c) ->
    c.events.maxOf { e -> (0..100).maxOf { i -> c.channels.maxOf { f -> abs(f(e.plateauStart+e.hold*i/100.0)-1) } } }
}.also { check(it.values.all { v -> v==0.0 }) }
val probes=listOf(1613.71,0.0,3000.0,1515.0,10.73,1625.0)
val expected=probes.map { t -> restored.channels.map { it(t) } }
check(probes.reversed().map { t -> restored.channels.map { it(t) } }.reversed()==expected)
results["randomAccessOrderIndependent"]=true

// Exercise genuine compiler failure + stale manual-edit rejection in a disposable
// session, using the very same transaction class as the live document tools.
val temp=Files.createTempDirectory("function-study-live-check-").toFile()
var text="blank"
val transaction=FunctionStudy(temp,{text},{ old,next -> if(text==old) {text=next;true} else false })
try {
    val good=transaction.edit("empty",FunctionStudy.hash(text),sources.getValue("01-create"),"runtime test").revision
    val accepted=text
    val bad=runCatching { transaction.edit(good,FunctionStudy.hash(text),"this is not Kotlin !!!","deliberate compiler failure") }.exceptionOrNull()
    check(bad!=null && transaction.currentId()==good && text==accepted)
    text=accepted+"\n// manual edit\n"
    val conflict=runCatching { transaction.edit(good,FunctionStudy.hash(accepted),accepted,"stale agent") }.exceptionOrNull()
    check(conflict?.message?.contains("Conflict")==true && text.endsWith("// manual edit\n") && transaction.currentId()==good)
    results["failedCompilationRetainedWorkingRevision"]=true
    results["staleAgentDidNotOverwriteManualEdit"]=true
} finally { temp.deleteRecursively() }
results["method"]="Independent compilation of saved model-authored Kotlin; <=11ms comparison grid, exact landmarks, clock finite differences at h=0.1ms. Sampled evidence, not a proof against arbitrary hidden features."
results["revisions"]=turns.associateWith(::revisionFor)
File(root,"conversations/independent-verification.json").writeText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(results))
println("FUNCTION_STUDY_INDEPENDENT_CHECKS_PASS "+mapper.writeValueAsString(results))
