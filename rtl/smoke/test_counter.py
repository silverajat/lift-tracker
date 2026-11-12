"""cocotb smoke test: the counter resets, counts when enabled, holds when not."""
import cocotb
from cocotb.clock import Clock
from cocotb.triggers import RisingEdge, ReadOnly


@cocotb.test()
async def counts_and_holds(dut):
    cocotb.start_soon(Clock(dut.clk, 10, unit="ns").start())
    dut.rst.value = 1
    dut.en.value = 0
    for _ in range(2):
        await RisingEdge(dut.clk)
    dut.rst.value = 0

    dut.en.value = 1
    for _ in range(5):
        await RisingEdge(dut.clk)
    await ReadOnly()
    assert int(dut.count.value) == 5, f"expected 5, got {int(dut.count.value)}"

    await RisingEdge(dut.clk)
    dut.en.value = 0
    for _ in range(3):
        await RisingEdge(dut.clk)
    await ReadOnly()
    assert int(dut.count.value) == 6, "count must hold while en is low"
