import afield.stdlib.*

// Biham–Middleton–Levine traffic: red cars move right, blue cars move down, in turns.
// 0.20 flows freely, 0.45 jams; 0.32 sits near the transition between them.
var bml = ShaderBmlSystem() {
    width = 512
    height = 512
    density = 0.32f
}

// this installs the editable update rule in this box's fragment tab
bml.installInto()
