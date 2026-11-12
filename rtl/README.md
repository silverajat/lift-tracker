# RTL

Verilog for the IMU pipeline, tested with cocotb against the Python golden model.

## Tools (in WSL Ubuntu)

```
sudo apt update && sudo apt install -y iverilog gtkwave python3-venv
python3 -m venv ~/rtl-env && source ~/rtl-env/bin/activate
pip install cocotb pytest
```

## Smoke test

`smoke/` is a tiny counter with one cocotb test. It only checks the tools work:

```
cd rtl/smoke && make
```

You should see `TESTS=1 PASS=1 FAIL=0`.

## Plan

R1 UART (tx + rx) with cocotb tests → R2 full pipeline → R3 SymbiYosys proofs → R4 FPGA demo.
