package afield.four.neuralrendering

/** CPU contracts and normal renderer startup with the optional backend explicitly disabled. */
object OpenDlssNrSmokeTest {
    @JvmStatic fun main(args: Array<String>) {
        for(value in listOf(-65504f,-10f,0f,1f,30f,65504f)) {
            check(OpenDlssNrControls(tone=value,structure=value,skin=value).structure==value)
        }
        for(value in listOf(Float.NaN,Float.POSITIVE_INFINITY,65505f,-65505f)) {
            check(runCatching {OpenDlssNrControls(tone=value)}.isFailure)
            check(runCatching {OpenDlssNrControls(structure=value)}.isFailure)
            check(runCatching {OpenDlssNrControls(skin=value)}.isFailure)
        }
        for(style in listOf(-1,3))check(runCatching {OpenDlssNrControls(style=style)}.isFailure)
        check(runCatching {OpenDlssNrControls(intensity=2f)}.isFailure)
        println("PASS NR controls: extended finite half range, styles, nonfinite rejection")
        if(args.firstOrNull()=="--disabled-renderer") {
            System.setProperty("afield.opendlssnr.disabled","true")
            afield.four.util.Options.enableHDR=false
            val window=afield.four.util.Window("Field without OpenDLSS-NR")
            val renderer=afield.four.Renderer(window)
            try {
                val available=OpenDlssNrSupport.availability(renderer)
                check(!available.ready && available.reason.contains("disabled"))
                check("VK_NV_cooperative_matrix2" !in renderer.device.enabledExtensions)
                println("PASS NR disabled: normal Field Vulkan renderer starts, optional features omitted")
            } finally {renderer.cleanup();window.cleanup()}
        }
    }
}
