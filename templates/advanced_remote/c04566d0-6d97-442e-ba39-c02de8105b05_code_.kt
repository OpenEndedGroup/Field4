import afield.knownValues
import afield.dynamicRoutes
import java.io.File

// you have to replace the wierd string of characters below with your Variant Launch key

knownValues["launchar_key"] = "brzT7HNGBnGDSbDbkTBbc1xPOeguKykq"

// here <NAME> = Desktop, but it can be anything that makes sense to you
// you are mapping /Users/marc/Desktop => /local/Desktop on our server
dynamicRoutes.addRoute("Desktop", File("/Users/marc/Desktop/"))
