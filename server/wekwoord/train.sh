#!/usr/bin/env bash
# Traint het wekwoord "Hey Fire" / "OK Fire" voor openWakeWord (draait op de tablet).
# Volgt de officiële trainingsnotebook van openWakeWord, maar dan als script voor de CPU.
# Duurt een paar uur en heeft ~25 GB schijf nodig. Uitkomst: uit/hey_fire.onnx
set -euo pipefail
cd "$(dirname "$0")"
WERK="$PWD/werk"; mkdir -p "$WERK" uit
cd "$WERK"

N_SAMPLES=${N_SAMPLES:-20000}
STEPS=${STEPS:-50000}

stap() { printf '\n\033[1;33m==> %s\033[0m\n' "$*"; }

stap "Python 3.10-omgeving (de trainingscode werkt niet met nieuwere versies)"
command -v uv >/dev/null || sudo pacman -S --needed --noconfirm uv
sudo pacman -S --needed --noconfirm git wget espeak-ng ffmpeg
[ -d venv ] || uv venv --python 3.10 venv
. venv/bin/activate
PIP="uv pip install"

stap "Code ophalen"
[ -d piper-sample-generator ] || git clone -b v2.0.0 https://github.com/rhasspy/piper-sample-generator
[ -f piper-sample-generator/models/en_US-libritts_r-medium.pt ] || wget -q -O piper-sample-generator/models/en_US-libritts_r-medium.pt \
  https://github.com/rhasspy/piper-sample-generator/releases/download/v2.0.0/en_US-libritts_r-medium.pt
[ -d openwakeword ] || git clone https://github.com/dscripka/openWakeWord openwakeword

$PIP --index-url https://download.pytorch.org/whl/cpu torch==2.1.2 torchaudio==2.1.2
$PIP piper-phonemize webrtcvad "setuptools<70"
$PIP -e ./openwakeword --no-deps
$PIP onnxruntime tqdm scipy scikit-learn pyyaml numpy==1.26.4 \
  mutagen==1.47.0 torchinfo==1.8.0 torchmetrics==1.2.0 speechbrain==0.5.14 \
  audiomentations==0.33.0 torch-audiomentations==0.11.0 acoustics==0.2.6 \
  pronouncing==0.2.0 datasets==2.14.6 pyarrow==14.0.2 deep-phonemizer==0.0.19 soundfile onnx==1.15.0

MODELS=openwakeword/openwakeword/resources/models; mkdir -p $MODELS
for f in embedding_model.onnx melspectrogram.onnx embedding_model.tflite melspectrogram.tflite; do
  [ -f $MODELS/$f ] || wget -q -O $MODELS/$f https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/$f
done

stap "Achtergrondgeluid, galm en negatieve voorbeelden downloaden"
python - <<'PY'
import os, numpy as np, scipy.io.wavfile, datasets
from tqdm import tqdm
if not os.path.exists("mit_rirs"):
    os.makedirs("mit_rirs")
    for row in tqdm(datasets.load_dataset("davidscripka/MIT_environmental_impulse_responses", split="train", streaming=True)):
        name = row["audio"]["path"].split("/")[-1]
        scipy.io.wavfile.write(os.path.join("mit_rirs", name), 16000, (row["audio"]["array"]*32767).astype(np.int16))
if not os.path.exists("fma"):
    os.makedirs("fma")
    ds = iter(datasets.load_dataset("rudraml/fma", name="small", split="train", streaming=True)
              .cast_column("audio", datasets.Audio(sampling_rate=16000)))
    for i in tqdm(range(3 * 3600 // 30)):  # ~3 uur muziek
        row = next(ds)
        name = row["audio"]["path"].split("/")[-1].replace(".mp3", ".wav")
        scipy.io.wavfile.write(os.path.join("fma", name), 16000, (row["audio"]["array"]*32767).astype(np.int16))
PY
if [ ! -d audioset_16k ]; then
  mkdir -p audioset audioset_16k
  wget -q -O audioset/bal_train09.tar https://huggingface.co/datasets/agkphysics/AudioSet/resolve/main/bal_train09.tar
  tar -xf audioset/bal_train09.tar -C audioset
  find audioset -name '*.flac' -print0 | xargs -0 -P4 -I{} sh -c 'ffmpeg -loglevel error -y -i "$1" -ar 16000 -ac 1 "audioset_16k/$(basename "$1" .flac).wav"' _ {}
fi
HF=https://huggingface.co/datasets/davidscripka/openwakeword_features/resolve/main
[ -f openwakeword_features_ACAV100M_2000_hrs_16bit.npy ] || wget -q --show-progress $HF/openwakeword_features_ACAV100M_2000_hrs_16bit.npy
[ -f validation_set_features.npy ] || wget -q --show-progress $HF/validation_set_features.npy

stap "Trainingsinstellingen"
python - <<PY
import yaml
c = yaml.safe_load(open("openwakeword/examples/custom_model.yml"))
c.update({
    "model_name": "hey_fire",
    "target_phrase": ["hey fire", "okay fire", "ok fire"],
    # Lijkt erop maar is het niet: hier moet hij juist níet op reageren (veel Nederlands in huis)
    "custom_negative_phrases": ["fire", "hey", "okay", "vier", "hee vier", "hey vier", "oké vier", "fijn", "hey fijn",
                                "vuur", "hey fijne", "hey siri", "hey google", "ok google", "alexa", "hey firefox",
                                "hey friday", "hey frank", "hey fred", "okay fine", "hey fine", "fire alarm", "on fire"],
    "n_samples": $N_SAMPLES,
    "n_samples_val": max(1000, $N_SAMPLES // 10),
    "steps": $STEPS,
    "target_accuracy": 0.7,
    "target_recall": 0.5,
    "max_negative_weight": 1500,
    "output_dir": "./hey_fire",
    "piper_sample_generator_path": "./piper-sample-generator",
    "rir_paths": ["./mit_rirs"],
    "background_paths": ["./audioset_16k", "./fma"],
    "background_paths_duplication_rate": [1, 1],
    "false_positive_validation_data_path": "validation_set_features.npy",
    "feature_data_files": {"ACAV100M_sample": "openwakeword_features_ACAV100M_2000_hrs_16bit.npy"},
})
yaml.safe_dump(c, open("hey_fire.yml", "w"))
PY

stap "Voorbeelden maken, mengen en trainen"
T=openwakeword/openwakeword/train.py
# Voorbeelden en kenmerken kosten uren: niet opnieuw als ze er al zijn
if [ ! -f hey_fire/hey_fire/negative_features_test.npy ]; then
  python $T --training_config hey_fire.yml --generate_clips
  python $T --training_config hey_fire.yml --augment_clips
fi
python $T --training_config hey_fire.yml --train_model || true  # de tflite-omzetting aan het eind mag falen

cp hey_fire/hey_fire.onnx ../uit/hey_fire.onnx
echo "Klaar: $(cd ..; pwd)/uit/hey_fire.onnx"
