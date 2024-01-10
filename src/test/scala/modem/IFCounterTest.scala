package modem

import chisel3._
import chisel3.util._
import chiseltest._

import baseband.{BasebandModemParams}

import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.immutable.Seq
import scala.util.Random

class IFCounterTest extends AnyFlatSpec with ChiselScalatestTester {
  it should "Test IF counter" in {
    test(new IFCounter(new BasebandModemParams(adcBits = 8))) { ifCounter =>
      ifCounter.clock.setTimeout(2000)

      val rand = new Random(69420)
      var refAdcTicks = 0
      var refIfTicksPrevPacket = 0
      var refAdcTicksPrevPacket = 0

      // Test multiple SOPs in a row for reset
      // Also test adcTicks
      ifCounter.io.sop.poke(true.B)
      for (_ <- 0 until 10) {
        ifCounter.clock.step()
        refAdcTicks = refAdcTicks + 1
        assert(ifCounter.io.state.output.adcTicks.peek().litValue.toInt == refAdcTicks)
        assert(ifCounter.io.state.output.ifTicksPacket.peek().litValue.toInt == 0)
        assert(ifCounter.io.state.output.adcTicksPacket.peek().litValue.toInt == 0)
        assert(ifCounter.io.state.output.ifTicksPrevPacket.peek().litValue.toInt == 0)
        assert(ifCounter.io.state.output.adcTicksPrevPacket.peek().litValue.toInt == 0)
        assert(ifCounter.io.state.output.thresholdInterrupt.peek().litValue.toInt == 0)
      }

      // Randomly test different threshold values
      for (_ <- 1 until 20) {
        val ifTickThreshold = rand.nextInt(500)

        var refIfTicksPacket = 0
        var refAdcTicksPacket = 0
        var cur = rand.nextInt(255)

        // Setup SOP
        ifCounter.io.sop.poke(true.B)
        ifCounter.io.data.poke(cur.U)
        ifCounter.io.state.input.ifTickThreshold.poke(ifTickThreshold.U)

        // Next clock cycle
        ifCounter.clock.step()

        // Asserts
        refAdcTicks = refAdcTicks + 1
        assert(ifCounter.io.state.output.adcTicks.peek().litValue.toInt == refAdcTicks)
        assert(ifCounter.io.state.output.ifTicksPacket.peek().litValue.toInt == refIfTicksPacket)
        assert(ifCounter.io.state.output.adcTicksPacket.peek().litValue.toInt == refAdcTicksPacket)
        assert(ifCounter.io.state.output.ifTicksPrevPacket.peek().litValue.toInt == refIfTicksPrevPacket)
        assert(ifCounter.io.state.output.adcTicksPrevPacket.peek().litValue.toInt == refAdcTicksPrevPacket)
        assert(ifCounter.io.state.output.thresholdInterrupt.peek().litValue.toInt == 0)

        ifCounter.io.sop.poke(false.B)

        // For each tick, check ref values
        while (refIfTicksPacket < ifTickThreshold) {
          val next = rand.nextInt(255)

          // Update counters
          refAdcTicks = refAdcTicks + 1
          refAdcTicksPacket += 1
          if (cur < 128 && next >= 128) {
            refIfTicksPacket += 1
          }
          
          ifCounter.io.data.poke(next.U)
          ifCounter.clock.step()
          assert(ifCounter.io.state.output.adcTicks.peek().litValue.toInt == refAdcTicks)
          assert(ifCounter.io.state.output.ifTicksPacket.peek().litValue.toInt == refIfTicksPacket)
          assert(ifCounter.io.state.output.adcTicksPacket.peek().litValue.toInt == refAdcTicksPacket)
          assert(ifCounter.io.state.output.ifTicksPrevPacket.peek().litValue.toInt == refIfTicksPrevPacket)
          assert(ifCounter.io.state.output.adcTicksPrevPacket.peek().litValue.toInt == refAdcTicksPrevPacket)
          assert(ifCounter.io.state.output.thresholdInterrupt.peek().litValue.toInt == 0)

          cur = next 
        }

        // Check that adcTicks does not keep ticking after reaching ifTicks
        for (i <- 0 until 10) {
          ifCounter.clock.step()
          refAdcTicks = refAdcTicks + 1
          assert(ifCounter.io.state.output.adcTicks.peek().litValue.toInt == refAdcTicks)
          assert(ifCounter.io.state.output.ifTicksPacket.peek().litValue.toInt == refIfTicksPacket)
          assert(ifCounter.io.state.output.adcTicksPacket.peek().litValue.toInt == refAdcTicksPacket)
          assert(ifCounter.io.state.output.ifTicksPrevPacket.peek().litValue.toInt == refIfTicksPrevPacket)
          assert(ifCounter.io.state.output.adcTicksPrevPacket.peek().litValue.toInt == refAdcTicksPrevPacket)
          if (i == 0) {
            assert(ifCounter.io.state.output.thresholdInterrupt.peek().litValue.toInt == 1)
          } else {
            assert(ifCounter.io.state.output.thresholdInterrupt.peek().litValue.toInt == 0)
          }
        }

        refIfTicksPrevPacket = refIfTicksPacket
        refAdcTicksPrevPacket = refAdcTicksPacket
      }
    }
  }
}
