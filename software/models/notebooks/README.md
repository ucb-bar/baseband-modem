# Jupyter Notebooks

These notebooks serve to document the development process of the entire RX chain as well as parts of the TX chain. It's intended and was used to form the entirety of the "Modem" which transmits bits on the TX side and reads ADC signals, finds packet starts, and provides bytes to the "Baseband" on the RX side. If the ADC sample rate (which was assumed to be the clock of the entire BasebandModem) or resolution changes, these notebooks should be flexible enough and documented well enough to update the current Modem (mostly FIR coefficients and some constants) to the new specs.

The notebooks were written and should be read in the following order.
- <code>rfsim.ipynb</code> - Demonstrates basic FM modulation and demodulation
- <code>lrwpan.ipynb</code> - The entire Modem RX chain and a TX packet generator
- <code>ble.ipynb</code> - Most of the Modem RX chain, uses an incorrect packet format just for development, used for FIR coefficients

For those trying out Chisel and wanting a local development environment as well as a potential place to document RTL, we have a Docker image to run <code>RTL.ipynb</code>. You can build and run it using something like the following commands.
```
docker build -t baseband ./
docker run -it --rm -p 8888:8888 baseband
```
