/*
Each mode tests a different part of the same per-pixel correspondence:
0 accumulated beauty; 1 backward motion, including each ball; 2 previous UV,
which breaks at moving silhouettes; 3 green valid history, red invalid/off-screen reprojection.
*/
val names = ["beauty", "backward motion", "previous UV", "availability"]
frames {
    while (true) {
        for (mode in 0..3) {
            chain.setPush("display", "mode", mode)
            println("mode " + mode + " - " + names[mode])
            repeat(150) { frame() }
        }
    }
}
