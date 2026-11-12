# Panel v1.1: LED controller + gym pod, one JLCPCB order

**The full order guide is `JLCPCB_ORDER.md`.** This is the short version.

Upload these three files:

| Step on jlcpcb.com | File |
|---|---|
| PCB (Gerber) | `fabrication/panel_v1.1_JLCPCB_Gerbers.zip` |
| PCB Assembly → BOM | `assembly/panel_BOM_JLCPCB.csv` |
| PCB Assembly → CPL | `assembly/panel_CPL_JLCPCB.csv` |

PCB settings:
- Panel 141.2 × 78.1 mm, 4 layers, 1.6 mm thick, ENIG.
- Copper 1 oz outer / 0.5 oz inner.
- Vias 0.25 / 0.45 mm.
- 5 panels.
- Different design: 2. Delivery format: Panel by Customer.

Assembly settings:
- Standard PCBA, top side.
- Assemble 2 panels.
- Edge rails and fiducials are added by customer (already on the panel).

v1.1 changes to the gym pod: battery protection (DW01A + FS8205A), a keyed JST-SH battery socket, motor pads on the bottom, and the board grew to 38 × 27 mm. If the JLC draft already has the v1 files, replace all three.
