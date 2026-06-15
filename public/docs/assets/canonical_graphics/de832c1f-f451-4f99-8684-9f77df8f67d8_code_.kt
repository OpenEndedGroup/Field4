import splat.*
var table = readPlyAsFloatTable("/Users/marc/Desktop/lego_point_cloud_6999.ply")
_print(table.offsets)

var ll = listOf("f_dc_0", "f_dc_1", "f_dc_2", "opacity", "scale_0", "scale_1", "scale_2", "rot_0", "rot_1", "rot_2", "rot_3", "f_rest_0", "f_rest_1", "f_rest_2", "f_rest_3", "f_rest_4")
var buffer = table.subsetFloatBufferColumns(ll)
var pos = table.subsetFloatBufferColumns(listOf("x", "y", "z", "opacity"))

var numPoints = buffer.limit()/16

// numPoints = 10
//(mat4?)
// xyz_rgba_sss_rrrr

buffer.limit(numPoints*16)
pos.limit(numPoints*4)

prop<FloatBuffer>(splatBuffer) = buffer
prop<FloatBuffer>(splatPos) = pos
prop<Int>(splatNum) = numPoints

parents.first().name = "bob"