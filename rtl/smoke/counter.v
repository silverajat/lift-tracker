// Tiny 8-bit counter. Only purpose: prove that Icarus Verilog + cocotb
// work on this machine before starting R1 (the UART).
`timescale 1ns/1ps
module counter (
    input  wire       clk,
    input  wire       rst,    // synchronous, active high
    input  wire       en,
    output reg  [7:0] count
);
    always @(posedge clk) begin
        if (rst)      count <= 8'd0;
        else if (en)  count <= count + 8'd1;
    end
endmodule
