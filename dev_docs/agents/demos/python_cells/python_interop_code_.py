# JEP embeds CPython *in-process*, so java.lang -- and anything else on Field's
# classpath -- is just another module, and this cell can reach its own Box.
from java.lang import System
from afield.python import PythonInterface

box = PythonInterface.thisBox

print("\n".join([
    f"JVM       {System.getProperty('java.version')}",
    f"this box  {box.getName()}",
]))
