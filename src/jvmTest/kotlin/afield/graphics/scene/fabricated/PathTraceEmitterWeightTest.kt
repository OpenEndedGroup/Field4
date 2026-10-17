package afield.graphics.scene.fabricated

import afield.math.linalg.Vector3f
import kotlin.test.Test
import kotlin.test.assertEquals

class PathTraceEmitterWeightTest {
    private fun PathTraceScene.addLamp(material: Int,x: Float) {
        triangle(Vector3f(x,0f,0f),Vector3f(x+1f,0f,0f),Vector3f(x,1f,0f),material)
    }

    @Test fun equalAreaLampsAreSelectedInProportionToTheirTexturedRadiance() {
        val scene=PathTraceScene()
        val dim=scene.texture(PathTraceTexture(1,1,floatArrayOf(.25f,.25f,.25f,1f)))
        val dimMaterial=scene.material(PathTraceMaterial.emissive(1f,1f,1f).copy(emissionTexture=dim))
        val fullMaterial=scene.material(PathTraceMaterial.emissive(1f,1f,1f))
        scene.addLamp(dimMaterial,0f);scene.addLamp(fullMaterial,2f)
        val packed=scene.pack()
        val probabilities=packed.triangles.indices.associate { i ->
            packed.triangles[i].material to packed.emitterLookup[2*i]
        }
        assertEquals(2,packed.emitterCount)
        assertEquals(.2f,probabilities.getValue(dimMaterial),1e-6f)
        assertEquals(.8f,probabilities.getValue(fullMaterial),1e-6f)
    }

    @Test fun textureMeanIncludesBlackTexelsAndBlackFactorsEmitNothing() {
        val scene=PathTraceScene()
        val texture=scene.texture(PathTraceTexture(2,1,floatArrayOf(0f,0f,0f,1f,2f,2f,2f,1f)))
        val textured=scene.material(PathTraceMaterial.emissive(1f,1f,1f).copy(emissionTexture=texture))
        val constant=scene.material(PathTraceMaterial.emissive(1f,1f,1f))
        val black=scene.material(PathTraceMaterial.emissive(0f,0f,0f).copy(emissionTexture=texture))
        scene.addLamp(textured,0f);scene.addLamp(constant,2f);scene.addLamp(black,4f)
        val packed=scene.pack()
        assertEquals(2,packed.emitterCount)
        for(i in packed.triangles.indices) {
            val expected=if(packed.triangles[i].material==black)0f else .5f
            assertEquals(expected,packed.emitterLookup[2*i],1e-6f)
        }
    }
}
