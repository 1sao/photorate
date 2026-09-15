# Photo Rate: a lazy person's way of cataloguing stuff to buy or not to buy again

Open your phone's camera app, take a picture of a coffee bag, with your free hand displaying a
thumbs up.

Next time you're in the store and don't remember which coffee struck your fancy the last time, open
Photo Rate to quickly check what you already tried and if you liked it.

## Features

- Scan your whole gallery to retroactively find images with scores you took years ago.
- Represent a 1-5 score with a hand gesture to filter your scored images later. A 5 is a thumb
  pointing straight up; a 3 is a thumb pointing to the side horizontally or an OK sign; a thumb
  pointing slightly down is a 2; and so on.
- Describe what you're looking for in the search bar and get the most closely matched scored images.
- Never worry about a tech billionaire learning about your taste in coffee -- everything is
  processed locally, with your own phone's powers alone.

## Technical details

Built with KMP. The Android part is done. The SwiftUI part is under construction.

Uses LiteRT or ONNX (configurable) for inference.

#### LiteRT

Found it to be the best option for this use case.

#### ONNX

Worked great, but no running on GPU or NPU without gutting the model with manual tweaks and
sacrificing a lot of precision in the process.

#### MediaPipe

Was the initial model used for hand landmarking. Unfortunately, the simplicity of Android setup was
not matched by the precision and quality of the model's results. To be dropped completely. Making it
work on iOS was a complicated mess anyway.

### **Beware! Beware!**

The code in recognition implementation modules (`imageRecognitionComponentLiteRt`,
`imageRecognitionComponentOnnx`, `imageRecognitionComponentMediaPipe`), as well as Python scripts
for re-exporting and quantizing local models, are very much non-organic barely reviewed AI slop.
Hence, that code is horrible and should never serve as an example. Sometime in future™ it will be
cleaned up. As for any other code, please feel free to use as an inspiration!

### Models used

- [RTMPose by OpenMMLab](https://github.com/open-mmlab/mmpose/tree/main/projects/rtmpose) with a
  manual custom export.
- [MobileCLIP](https://github.com/apple/ml-mobileclip) with
  a [custom export by Xenova](https://huggingface.co/Xenova/mobileclip_b) and
  a [custom export by anton96vice](https://huggingface.co/anton96vice/mobileclip2_tflite), both
  being manually tweaked further.