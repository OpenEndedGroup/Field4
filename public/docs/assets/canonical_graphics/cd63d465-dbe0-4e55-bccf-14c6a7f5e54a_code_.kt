
val sorter = GpuRadixSort()

var sourceBuffer = buffer {
    size = 200.ints
    storage()
    addressable()
    hosted()
    dest()
}.buffer

sourceBuffer.write { 
    200 {
        putInt(2000-it)
    }
}

renderer.renderList.others["sort"] = Target { command, index -> 
    sorter.sort(command, sourceBuffer, 200)
}

sourceBuffer.read_ints {  
    _print(this.get(0))
}