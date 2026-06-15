navigator.mediaDevices.getUserMedia({ audio: true })
  .then(stream => {
    const audioContext = new AudioContext();
    const source = audioContext.createMediaStreamSource(stream);

    // Create a ScriptProcessorNode with a buffer size of 4096 samples
    const bufferSize = 4096;
    const processor = audioContext.createScriptProcessor(bufferSize, 1, 1);

    // Connect the nodes: source -> processor -> destination (optional)
    source.connect(processor);
    processor.connect(audioContext.destination);

    // This event is called whenever the processor has new audio data
    processor.onaudioprocess = event => {
      // Get the audio data from the first channel (assuming mono input)
      const inputBuffer = event.inputBuffer;
      const audioData = inputBuffer.getChannelData(0);

      // Convert the Float32Array to a regular array (if needed)
      const dataArray = Array.from(audioData);

      // Do something with the audio data array (e.g., log it)
      console.log(dataArray);
    };
  })
  .catch(error => {
    console.error('Error accessing the microphone:', error);
  });