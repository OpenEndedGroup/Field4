import afield.graphics.render.neural.OpenDlssNrStudy
import java.nio.file.Path
import kotlin.io.path.createDirectories

// Run beneath the live study box. The scene remains editable after recording.
val study=lab
check(study.availability.ready) {study.availability.reason}
check(!study.recording) {"A recording is already running"}
val folder=Path.of("build/opendlssnr-study/clip").toAbsolutePath().also {it.createDirectories()}
val controls=study.controls.copy()
val grade=study.grade.toString()
val previousTime=study.time
study.recording=true
study.animate=false
frames {
    try {
        for(i in 0 until 180) {
            check(study.controls==controls && study.grade.toString()==grade) {"Controls changed during capture; record again"}
            study.time=i/30f
            val saved=study.saveFrame(folder.resolve("frame-%03d.png".format(i)))
            while(!saved.isDone)frame()
            saved.join()
            if(i%30==0)println("Recorded $i / 180")
        }
        println("NR STUDY RECORD DONE: 180 frames at 30 fps, 2304 × 832; $folder")
    } finally {
        study.time=previousTime
        study.recording=false
    }
}
