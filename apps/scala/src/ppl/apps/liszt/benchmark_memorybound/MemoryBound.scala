package ppl.apps.liszt.benchmark_memorybound

import ppl.dsl.deliszt.datastruct.scala._
import ppl.dsl.deliszt.{DeLisztApplicationRunner, DeLisztApplication, DeLisztExp}

object MemoryBoundRunner extends DeLisztApplicationRunner with MemoryBound

object MemoryBound extends DeLisztApplication {
	val position = FieldWithLabel[Vertex,Vec[_3,Float]]("position")
	val outc = FieldWithConst[Cell,Float](0)
	
	def main() {
		var t = 0.f
		val deltat = 0.2f

		Print("Running 2-level sequential pattern.")
		while (t < 1.f) {
			for (c <- cells(mesh)) {
				for (v <- vertices(c)) {
					val p = position(v)
					outc(c) = p(_0)
				}
			}
			t += deltat
		}
		
	}
}