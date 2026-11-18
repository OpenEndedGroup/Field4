import Foundation
import CoreGraphics

@main struct ReviewTests {
    static func main() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("drawingon-review-tests-"+UUID().uuidString)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        var count = 0
        func expect(_ condition: @autoclosure () throws -> Bool, _ message: String, line: Int = #line) throws {
            if try !condition() { throw ReviewFailure("test line \(line): \(message)") }
        }
        func fixture(_ events: [(Double, String, [String: Any])]) throws -> (ReviewCapture, URL) {
            let name=UUID().uuidString,input=root.appendingPathComponent(name+".ndjson")
            var bytes=Data()
            for (index,item) in events.enumerated() {
                bytes.append(try JSONSerialization.data(withJSONObject:["v":1,"session":"test-session","seq":index+1,"t":item.0,"type":item.1,"data":item.2]));bytes.append(10)
            }
            try bytes.write(to:input)
            return (try ReviewCapture(input:input,assetsDirectory:root.appendingPathComponent(name+"-assets")),input)
        }
        func sample(_ id:Int,_ x:Double,_ y:Double)->[String:Any] {
            ["id":id,"t":0.0,"x":x,"y":y,"previousX":x,"previousY":y,"preciseX":x,"preciseY":y,"precisePreviousX":x,"precisePreviousY":y,"drawingX":x,"drawingY":y,"force":1.0,"maximumForce":1.0,"radius":1.0,"radiusTolerance":0.0,"phase":1,"tapCount":1,"input":2,"estimated":0,"expectingUpdates":0]
        }
        let viewport:[String:Any]=["width":100.0,"height":100.0,"transform":jsonObject(DrawingTransform.identity)]
        let layers:[String:Any]=["layers":[["id":"one","name":"Pencil","visible":true]],"active":"one","solo":NSNull()]
        func line(_ tool:String="freehand")->[(Double,String,[String:Any])] {
            [(0,"canvas.viewport",viewport),(0.1,"layers.state",layers),(1,"stroke.begin",["stroke":"s","layer":"one","tool":tool]),(1.1,"stroke.samples",["stroke":"s","samples":[sample(1,10,20),sample(2,80,20)]]),(1.2,"stroke.end",["stroke":"s","cancelled":false])]
        }
        func pixel(_ image:CGImage,_ x:Int,_ y:Int)->Int {
            let bytes=image.dataProvider!.data! as Data
            return Int(bytes[y*image.bytesPerRow+x*4])
        }
        func state(_ capture:ReviewCapture)throws->ReviewState {
            var result=ReviewState();for event in capture.events {try result.apply(event)};return result
        }
        do {
            let (capture,_)=try fixture(line()+[(1.3,"stroke.prediction",["stroke":"s","samples":[sample(-1,99,99)]]),(1.4,"stroke.correct",["stroke":"s","samples":[sample(2,90,20)]])])
            let replay=try state(capture),image=try renderFrame(replay,assets:capture.assets,width:100)
            try expect(replay.scene.strokes[0].samples.count==2,"predictions must not enter measured ink")
            try expect(replay.scene.strokes[0].samples.last!.pointX==90,"correction replaces measured endpoint")
            try expect(pixel(image,40,20)>10 && pixel(image,40,80)==0,"PNG preserves UIKit top-left coordinates")
            count+=1
        }
        do {
            let (capture,_)=try fixture(line("line")+[(2,"canvas.transform",["transform":jsonObject(DrawingTransform(a:0,b:1,c:-1,d:0,tx:100,ty:0)),"viewport":viewport])])
            var replay=try state(capture);let image=try renderFrame(replay,assets:capture.assets,width:100)
            try expect(pixel(image,80,40)>10 && pixel(image,40,20)==0,"navigation rotates into recorded viewport")
            replay.scene.transform.tx=300
            let clipped=try renderFrame(replay,assets:capture.assets,width:100)
            try expect(pixel(clipped,80,40)==0,"offscreen geometry is cropped, never auto-framed")
            count+=1
        }
        do {
            let (capture,_)=try fixture(line()+[(2,"stroke.visibility",["stroke":"s","visible":false,"reason":"undo"]),(3,"canvas.appearance",["light":true,"opacity":0.4,"width":2.0])])
            var replay=try state(capture)
            try expect(replay.scene.strokes.count==1 && !replay.scene.strokes[0].visible,"undo keeps source stroke")
            let blank=try renderFrame(replay,assets:capture.assets,width:100)
            try expect(pixel(blank,40,20)==255,"light-mode background is white")
            replay.scene.apply("stroke.visibility",["stroke":"s","visible":true])
            let shown=try renderFrame(replay,assets:capture.assets,width:100)
            try expect(pixel(shown,40,20)<240,"light-mode ink uses subtractive blending")
            replay.scene.solo="other";try expect(replay.scene.visibleLayers.isEmpty,"solo affects visible layers")
            count+=1
        }
        do {
            var events=line("circle");events[3]=(1.1,"stroke.samples",["stroke":"s","samples":[sample(1,50,50),sample(2,70,50)]])
            let (capture,_)=try fixture(events),image=try renderFrame(state(capture),assets:capture.assets,width:100)
            try expect(pixel(image,50,30)>10 && pixel(image,50,50)==0,"circle uses center/radius intent")
            count+=1
        }
        func audioData(_ samples:[Float],rate:Double,recording:String,sampleTime:Int)->[String:Any] {
            var pcm=Data();for sample in samples {var bits=sample.bitPattern.littleEndian;withUnsafeBytes(of:&bits){pcm.append(contentsOf:$0)}}
            return ["recording":recording,"frames":samples.count,"channels":1,"sampleRate":rate,"sampleTime":sampleTime,"hostTimeValid":true,"pcm":pcm.base64EncodedString()]
        }
        do {
            var events:[(Double,String,[String:Any])]=[]
            for buffer in 0..<20 {
                let samples=(0..<2400).map{i in Float(0.2*sin(2*Double.pi*220*Double(i+buffer*2400)/48000))}
                events.append((100+Double(buffer)*0.05,"audio.buffer",audioData(samples,rate:48000,recording:"r",sampleTime:buffer*2400)))
                if buffer==1 {events.append((101.5,"canvas.viewport",viewport))}
            }
            events.append((103,"audio.buffer",audioData([Float](repeating:0.1,count:8000),rate:16000,recording:"r",sampleTime:99_000)))
            let (capture,_)=try fixture(events),spans=speechSpans(capture.audio,options:ReviewOptions())
            try expect(capture.audio.count==2,"gaps and format changes split audio runs")
            try expect(capture.audio[0].samples.count==16000,"resampling has no per-buffer rounding drift")
            try expect(spans.count==2 && abs(spans[0].start-100)<0.01 && abs(spans[0].end-101)<0.01,"VAD preserves capture time and audio gaps")
            try expect(capture.events.count==1,"late audio timestamp cannot truncate drawing replay")
            let early=chooseFrames(capture,speech:[SpeechSpan(start:1,end:2,run:0,thresholdDB:-40,noiseDB:-80)],options:ReviewOptions())
            try expect(early.frames[0].sequence==0,"early speech never reveals future drawing")
            let (lateVisual,_)=try fixture([(10,"canvas.viewport",viewport),(9,"layers.state",layers)])
            let lateChoices=chooseFrames(lateVisual,speech:[SpeechSpan(start:8,end:9.5,run:0,thresholdDB:-40,noiseDB:-80)],options:ReviewOptions())
            try expect(lateChoices.frames[0].sequence==0,"a regressed visual timestamp cannot reveal a future prefix")
            count+=1
        }
        do {
            let samples=(0..<48000).map{Float(0.2*sin(2*Double.pi*12000*Double($0)/48000))}
            let (capture,_)=try fixture([(0,"audio.buffer",audioData(samples,rate:48000,recording:"noise",sampleTime:0))])
            let values=capture.audio[0].samples.dropFirst(100),mean=values.reduce(0.0){$0+Double($1)*Double($1)}/Double(values.count)
            try expect(mean<0.0005,"low-pass attenuates out-of-band noise before downsampling")
            let wav=root.appendingPathComponent("test.wav");try writeWAV(values.prefix(1600),to:wav)
            let bytes=try Data(contentsOf:wav)
            try expect(bytes.count==3244 && String(decoding:bytes.prefix(4),as:UTF8.self)=="RIFF","WAV has correct PCM16 mono header and length")
            count+=1
        }
        do {
            let silence=AudioRun(recording:"r",start:50,samples:[Float](repeating:0,count:32000),sourceFirstSequence:1,sourceLastSequence:1,fallbackClock:false)
            try expect(speechSpans([silence],options:ReviewOptions()).isEmpty,"silence is not transcribed")
            let (capture,_)=try fixture((0..<10).map{(Double($0)*3,"canvas.viewport",viewport)})
            var options=ReviewOptions();options.maxFrames=2
            let choices=chooseFrames(capture,speech:[],options:options)
            try expect(choices.frames.count==2 && choices.frames.last!.sequence==10 && choices.candidates==10,"frame cap preserves coverage and reports omissions")
            count+=1
        }
        do {
            var bytes=Data();for value:Float in [10,20,0,90,20,0] {var bits=value.bitPattern.littleEndian;withUnsafeBytes(of:&bits){bytes.append(contentsOf:$0)}}
            let hash=FieldReferenceAssets.hash(bytes)
            let geometry=FieldGeometryDescription(type:"three/geometry",uid:"field",lines:FieldGeometry(attributes:["position":FieldAttribute(itemSize:3,buffer:0)]),buffers:FieldBuffers(generation:"one",bytes:bytes.count,entries:[FieldBufferEntry(type:"Float32Array",offset:0,count:6)]))
            let reference=FieldReferenceRecord(layer:"field:ipad",target:"ipad",title:"Plan",plane:FieldDrawingPlane(origin:[0,0,0],xAxis:[1,0,0],yAxis:[0,1,0],scale:1,offsetX:0,offsetY:0),geometry:geometry,asset:hash)
            let (capture,_)=try fixture([(0,"canvas.viewport",viewport),(1,"field.asset",["sha256":hash,"part":0,"parts":1,"bytes":bytes.count,"data":bytes.base64EncodedString()]),(2,"field.reference",["reference":jsonObject(reference)])])
            let image=try renderFrame(state(capture),assets:capture.assets,width:100)
            try expect(try capture.assets.read(hash)==bytes,"Field assets reconstruct without external cache")
            try expect(pixel(image,40,20)>200,"Field geometry renders on its drawing plane")
            count+=1
        }
        do {
            let (_,input)=try fixture(line()),original=try Data(contentsOf:input)
            var partial=original;partial.append(Data("{incomplete".utf8));try partial.write(to:input)
            let capture=try ReviewCapture(input:input,assetsDirectory:root.appendingPathComponent("tail-assets"))
            try expect(capture.warnings.count==1 && (try Data(contentsOf:input))==partial,"partial tail is reported without changing input")
            var corrupt=original;corrupt.append(Data("{broken}\n".utf8));try corrupt.write(to:input)
            var refused=false
            do {_=try ReviewCapture(input:input,assetsDirectory:root.appendingPathComponent("corrupt-assets"))} catch {refused=true}
            try expect(refused && (try Data(contentsOf:input))==corrupt,"complete corruption is refused and preserved")
            count+=1
        }
        print("Passed \(count) offline review tests")
    }
}
