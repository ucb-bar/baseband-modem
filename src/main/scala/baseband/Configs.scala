package baseband

import chisel3._
import chisel3.{withClock => withNewClock}
import chisel3.util._
import chisel3.experimental._
import freechips.rocketchip.config.Config
import freechips.rocketchip.diplomacy.{InModuleBody, LazyModule}
import freechips.rocketchip.subsystem.BaseSubsystem

trait CanHavePeripheryBasebandModem { this: BaseSubsystem =>

  // TODO: do we need to change this?
  // val adc_clock = p(BasebandModemKey).map {_ =>
  //   val adc_clock_io = InModuleBody {
  //     val adc_clock_io = IO(Input(Clock())).suggestName("adc_clock")
  //     adc_clock_io
  //   }
  //   adc_clock_io
  // }

  val baseband = p(BasebandModemKey).map { params =>
    val baseband = LazyModule(new BasebandModem(params, fbus.beatBytes))

    pbus.toVariableWidthSlave(Some("baseband")) { baseband.mmio }
    fbus.fromPort(Some("baseband"))() := baseband.mem
    ibus.fromSync := baseband.intnode

    val io = InModuleBody {
      // adc_clock.map({ a =>
      //   baseband.module.clock := a
      // })

      val io = IO(new BasebandModemAnalogIO(params)).suggestName("baseband")
      io <> baseband.module.io
      io
    }
    io
  }
}

class WithBasebandModem(params: BasebandModemParams = BasebandModemParams()) extends Config((site, here, up) => {
  case BasebandModemKey => Some(params)
})

/* Note: The following are commented out as they rely on importing chipyard, which no
         generator can do without having a circular import. They should  be added to
         files in: <chipyard root>/generators/chipyard/src/main/scala/<file>

         To use, you should then add the following to your config:
           new baseband.WithBasebandModem() ++
           new chipyard.iobinders.WithBasebandModemPunchthrough() ++
           new chipyard.harness.WithBasebandModemTiedOff ++

         Finally add the following to DigitalTop.scala:
           with baseband.CanHavePeripheryBasebandModem
           with sifive.blocks.devices.timer.HasPeripheryTimer
*/

/* Place this in IOBinders.scala for use
import baseband.{CanHavePeripheryBasebandModem, BasebandModemAnalogIO, BasebandModemParams}

class WithBasebandModemPunchthrough(params: BasebandModemParams = BasebandModemParams()) extends OverrideIOBinder({
  (system: CanHavePeripheryBasebandModem) => {
    val ports: Seq[BasebandModemAnalogIO] = system.baseband.map({ a =>
      val analog = IO(new BasebandModemAnalogIO(params)).suggestName("baseband")
      analog <> a
      analog
    }).toSeq
    (ports, Nil)
  }
})
*/

/* Note: Place this in HarnessBinders.scala for use
import baseband.{CanHavePeripheryBasebandModem, BasebandModemAnalogIO}

class WithBasebandModemTiedOff extends OverrideHarnessBinder({
  (system: CanHavePeripheryBasebandModem, th: HasHarnessSignalReferences, ports: Seq[BasebandModemAnalogIO]) => {
    ports.map { p => {
      p.data.rx.i.data := 0.U
      p.data.rx.q.data := 0.U
    }}
  }
})
 */
