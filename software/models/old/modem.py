import numpy as np
import matplotlib.pyplot as plt
from scipy.signal import butter, lfilter, freqz
import math
from fixedpoint import FixedPoint

# Wave Constants
A = 1
h = 0.5 # modulation index
F = 2e6 # 3 MHz, target frequency
F_offset = 250000 # 250 kHz offset from modulation
wF = 2*np.pi*F # Angular frequency 
F_symbol = 1e6 # 1 MHz = 1/(1 us) where BLE symbol time is 1 us
T_symbol = 1e-6 # Symbol time of 1us in seconds
F_sample = 20e6# 16MHz, sampling frequency
#F_sample = 400e6


# FIR Constants
bts = 0.5
oversampling_factor = 10 # FIR samples / symbol, must be a factor of F_cpu/F_symbol
symbol_span = 2  # 6 symbols covered in FIR
sampling_interval = (F_sample/F_symbol)/oversampling_factor # Number of cycles between samples


def butter_lowpass(cutoff, fs, order=5):
    nyq = 0.5 * fs
    normal_cutoff = cutoff / nyq
    b, a = butter(order, normal_cutoff, btype='low', analog=False)
    return b, a

def butter_lowpass_filter(data, cutoff, fs, order=5):
    b, a = butter_lowpass(cutoff, fs, order=order)
    y = lfilter(b, a, data)
    return y

def butter_bandpass(bandstart, bandstop, fs, order=5):
	nyq = 0.5 * fs
	normal_bandstart = bandstart / nyq
	normal_bandstop = bandstop / nyq
	b, a = butter(order, [normal_bandstart, normal_bandstop], btype='bandpass', analog=False)
	return b,a

def butter_bandpass_filter(data, bandstart, bandstop, fs, order=5):
	b, a = butter_bandpass(bandstart, bandstop, fs, order=order)
	y = lfilter(b, a, data)
	return y

def frequency_plot(wave):
	wave_fft = np.fft.fft(wave)/len(wave)
	wave_fft = wave_fft[range(int(len(wave)/2))]

	frequencies = np.arange(int(len(wave)/2))/(len(wave)/F_sample)
	plt.plot(frequencies, abs(wave_fft))
	plt.show()
 

# we switch symbols every F_cpu/F_symbol clock cycles
#gaussian_samples = [-1*F_symbol for x in range(oversampling_factor*symbol_span+1)] #Initialize to the value of sending zeros
gaussian_samples = [0 for x in range(oversampling_factor*symbol_span+1)] #Initialize to the value to 0s

gaussian_weights = [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.015625, 0.0625, 0.15625, 0.328125, 0.59375,0.9375, 1.265625, 1.484375, 1.484375, 1.265625, 0.9375, 0.59375, 0.328125, 0.15625, 0.0625, 0.015625, 0.0, 0.0,0.0, 0.0, 0.0, 0.0, 0.0]
gaussian_weights = [w / 10 for w in gaussian_weights]

def gaussian_fir(sample, cycle):
	if (cycle % sampling_interval == 0):
		gaussian_samples[1:len(gaussian_samples)] = gaussian_samples[0:len(gaussian_samples)-1]
		gaussian_samples[0] = sample
	return sum([sample * weight for (sample, weight) in zip(gaussian_samples, gaussian_weights)])

def coherent_demod(wave, cycle, data_wave):
	w1 = 2*np.pi*(F+F_offset)
	w0 = 2*np.pi*(F-F_offset)

	w1_cos_integral = []
	w1_cos_integral_tracker = 0

	w0_cos_integral = []
	w0_cos_integral_tracker = 0


	guess = []

	for i in range(cycle):
		w1_cos_integral_base = w1_cos_integral_tracker if i%(F_sample/F_symbol) != 0 else 0
		w1_cos_integral_tracker = wave[i] * np.cos(w1*i/F_sample) * 1/F_sample + w1_cos_integral_base
		w1_cos_integral.append(w1_cos_integral_tracker)
		
		w0_cos_integral_base = w0_cos_integral_tracker if i%(F_sample/F_symbol) != 0 else 0
		w0_cos_integral_tracker = wave[i] * np.cos(w0*i/F_sample) * 1/F_sample + w0_cos_integral_base
		w0_cos_integral.append(w0_cos_integral_tracker)

		if (i % (F_sample/F_symbol) == (F_sample/F_symbol) - 1):
			guess.append(1 if abs(w1_cos_integral_tracker) < abs(w0_cos_integral_tracker) else 0) # < if we initialize FIR to 0, > if we initialize FIR to the +/- 1*T_s value 

	print(data[0:len(data)-3])
	print(np.asarray(guess[3:])) 

	plt.plot(range(cycle), [abs(x) for x in w0_cos_integral], color='red')
	plt.plot(range(cycle), [abs(x) for x in w1_cos_integral], color='blue')
	plt.show()

	return guess

def envelope_detector(wave):
	double_square_wave = [2*(x**2) for x in wave]
	low_pass_1 = butter_lowpass_filter(double_square_wave, 3e6, F_sample)
	envelope = [np.sqrt(x) for x in low_pass_1]
	return envelope

def noncoherent_demod(wave, cycle, data_wave):
	w0_bandpass = butter_bandpass_filter(wave, F-1.95*F_offset, F-0.05*F_offset, F_sample)
	w0_envelope = envelope_detector(w0_bandpass)

	w1_bandpass = butter_bandpass_filter(wave, F+0.05*F_offset, F+1.95*F_offset, F_sample)
	w1_envelope = envelope_detector(w1_bandpass)

	experimental_bandpass = butter_bandpass_filter(wave, F, F+2*F_offset, F_sample, order=5)
	plt.plot(range(cycle), experimental_bandpass, color='black')
	plt.show()

	guess = []
	demod_wave = []
	tracking_sum = 0

	for i in range(cycle):
		tracking_sum = tracking_sum + (w1_envelope[i] - w0_envelope[i])
		demod_wave.append(1 if w1_envelope[i] > w0_envelope[i] else -1)

		if (i % (F_sample/F_symbol) == (F_sample/F_symbol) - 1):
			guess.append(1 if tracking_sum > 0 else 0) 
			tracking_sum = 0


	#plt.plot(range(cycle), wave, color='black')
	plt.plot(range(cycle), w0_bandpass, color='b')
	plt.plot(range(cycle), w1_bandpass, color='r')
	#plt.show()

	plt.plot(range(cycle), w0_envelope, color='cyan')
	plt.plot(range(cycle), w1_envelope, color='orange')

	plt.plot(range(cycle), data_wave, color='black')
	plt.plot(range(cycle), demod_wave, color='green')

	plt.show()

	print(np.asarray(guess[3:]))

def modulate(data):
	data_idx = 0
	cycle = 0 
	s_t = 0
	phi_t = 0
	wave = []
	data_wave = []
	data_fir_wave = []

	# time t = cycle count / clock frequency 

	#s(t) = A * cos(wF*t + phi(t))
	#phi(t) = h*np.pi*integral of sum of impulse contributions
	offset = []
	# Operating from the perspective that each loop iteration is a clock cycle of the DAC
	while (data_idx < len(data)):
		val_i = data[data_idx]
		a_i = 1 if val_i else -1
		fir_results = gaussian_fir(a_i*F_symbol, cycle)
		data_fir_wave.append(fir_results/F_symbol)
		phi_t = (fir_results * (1/F_sample)) + phi_t #a_i * (1/T_symbol) * (1/F_cpu) # 1/F_cpu is delta T 
		s_t = A * np.cos(wF*(cycle/F_sample) + h*np.pi*phi_t)
		wave.append(s_t)
		offset.append(h*np.pi*phi_t)
		data_wave.append(val_i)
		cycle = cycle + 1
		data_idx = math.floor(cycle / (F_sample / F_symbol)) #queue equivalent

	plt.plot(range(cycle), wave, color='black')
	plt.plot(range(cycle), data_wave, color='blue')
	plt.plot(range(cycle), data_fir_wave, color='purple')
	plt.show()

	return wave, cycle, data_fir_wave, offset

# DATA
data = [1,0,1,0,1,0,1,1,1,0,0,0,1,1,0,0,1,1,0,0,1,1,0,0]
#data = np.asarray(np.random.randint(2, size=10))
#data = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1]
#data = [1, 1, 0, 0, 0, 1, 1, 1, 0,1]

#(wave, cycle, data_wave) = modulate(data)
#coherent_demod(wave, cycle, data_wave)
#noncoherent_demod(wave, cycle, data_wave)

out = modulate(data)
print([int(FixedPoint(s * 31, True, 6, 0, str_base=2)) for s in out[0]])
print(out[3])
plt.show()
