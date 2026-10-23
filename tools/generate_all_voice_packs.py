import os
import zipfile
import win32com.client

PHRASES_RU = {
    "attention": "Внимание!",
    "dist_100": "Через 100 метров",
    "dist_200": "Через 200 метров",
    "dist_300": "Через 300 метров",
    "dist_500": "Через 500 метров",
    "dist_700": "Через 700 метров",
    "dist_800": "Через 800 метров",
    "dist_1000": "Через 1 километр",
    "cam_fixed": "Камера контроля скорости",
    "cam_red_light": "Контроль светофора",
    "cam_bus_lane": "Контроль полосы общественного транспорта",
    "cam_marking": "Контроль разметки",
    "cam_mobile": "Мобильная засада",
    "cam_complex": "Комплекс фотофиксации",
    "cam_zone_start": "Начало зоны контроля средней скорости",
    "cam_zone_end": "Конец зоны контроля средней скорости",
    "cam_dummy": "Муляж камеры",
    "cam_hazard": "Опасный участок дороги",
    "oncoming": "В спину",
    "slow_down": "Снижайте скорость!",
    "limit_20": "Ограничение 20",
    "limit_30": "Ограничение 30",
    "limit_40": "Ограничение 40",
    "limit_50": "Ограничение 50",
    "limit_60": "Ограничение 60",
    "limit_70": "Ограничение 70",
    "limit_80": "Ограничение 80",
    "limit_90": "Ограничение 90",
    "limit_100": "Ограничение 100",
    "limit_110": "Ограничение 110",
    "limit_120": "Ограничение 120",
    "limit_130": "Ограничение 130"
}

PHRASES_EN = {
    "attention": "Attention!",
    "dist_100": "In 100 meters",
    "dist_200": "In 200 meters",
    "dist_300": "In 300 meters",
    "dist_500": "In 500 meters",
    "dist_700": "In 700 meters",
    "dist_800": "In 800 meters",
    "dist_1000": "In 1 kilometer",
    "cam_fixed": "Speed camera",
    "cam_red_light": "Red light camera",
    "cam_bus_lane": "Bus lane enforcement",
    "cam_marking": "Road marking camera",
    "cam_mobile": "Mobile police trap",
    "cam_complex": "Complex enforcement camera",
    "cam_zone_start": "Average speed zone starts",
    "cam_zone_end": "Average speed zone ends",
    "cam_dummy": "Dummy camera",
    "cam_hazard": "Hazardous road section",
    "oncoming": "Rear enforcement",
    "slow_down": "Reduce your speed!",
    "limit_20": "Speed limit 20",
    "limit_30": "Speed limit 30",
    "limit_40": "Speed limit 40",
    "limit_50": "Speed limit 50",
    "limit_60": "Speed limit 60",
    "limit_70": "Speed limit 70",
    "limit_80": "Speed limit 80",
    "limit_90": "Speed limit 90",
    "limit_100": "Speed limit 100",
    "limit_110": "Speed limit 110",
    "limit_120": "Speed limit 120",
    "limit_130": "Speed limit 130"
}

PACK_CONFIGS = [
    {
        "id": "dmitry_brutal",
        "voice_type": "onecore",
        "voice_token": r"HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Speech_OneCore\Voices\Tokens\MSTTS_V110_ruRU_PavelM",
        "rate": -1,
        "pitch": -5,
        "phrases": PHRASES_RU,
        "desc": "Brutal male Russian voice (Pavel - deep baritone)"
    },
    {
        "id": "elena_standard",
        "voice_type": "sapi",
        "voice_name": "Microsoft Irina Desktop - Russian",
        "rate": 0,
        "pitch": 0,
        "phrases": PHRASES_RU,
        "desc": "Standard female studio Russian voice (Irina - balanced)"
    },
    {
        "id": "oxana_navigator",
        "voice_type": "sapi",
        "voice_name": "Microsoft Irina Desktop - Russian",
        "rate": 2,
        "pitch": 3,
        "phrases": PHRASES_RU,
        "desc": "Dynamic female navigator Russian voice (Irina - rapid)"
    },
    {
        "id": "retro_humor",
        "voice_type": "onecore",
        "voice_token": r"HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Speech_OneCore\Voices\Tokens\MSTTS_V110_ruRU_PavelM",
        "rate": 1,
        "pitch": -1,
        "phrases": PHRASES_RU,
        "desc": "Retro male Russian voice (Pavel - ironic pace)"
    },
    {
        "id": "alex_english",
        "voice_type": "sapi",
        "voice_name": "Microsoft David Desktop - English (United States)",
        "rate": 0,
        "pitch": 0,
        "phrases": PHRASES_EN,
        "desc": "English navigator male voice (David)"
    }
]

def generate_pack(config, out_zip_paths):
    pack_id = config["id"]
    print(f"Generating pack: {pack_id} ({config['desc']})...")
    
    synth = win32com.client.Dispatch("SAPI.SpVoice")
    token = win32com.client.Dispatch("SAPI.SpObjectToken")
    
    if config["voice_type"] == "onecore":
        token.SetId(config["voice_token"])
        synth.Voice = token
    else:
        for v in synth.GetVoices():
            if config["voice_name"].lower() in v.GetDescription().lower():
                synth.Voice = v
                break
                
    synth.Rate = config["rate"]
    pitch = config["pitch"]
    
    tmp_dir = os.path.join("build", "tmp_voice", pack_id)
    os.makedirs(tmp_dir, exist_ok=True)
    
    generated_files = []
    stream = win32com.client.Dispatch("SAPI.SpFileStream")
    
    for key, text in config["phrases"].items():
        wav_path = os.path.join(tmp_dir, f"{key}.wav")
        # 3 = SSFMCreateForWrite
        stream.Open(wav_path, 3, False)
        synth.AudioOutputStream = stream
        
        if pitch != 0:
            xml_text = f'<pitch absmiddle="{pitch}">{text}</pitch>'
            synth.Speak(xml_text, 8) # SVSFIsXML = 8
        else:
            synth.Speak(text, 0)
            
        stream.Close()
        generated_files.append((key, wav_path))
        
    print(f"  Synthesized {len(generated_files)} clips for {pack_id}")
    
    for zip_path in out_zip_paths:
        os.makedirs(os.path.dirname(zip_path), exist_ok=True)
        with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
            for key, fpath in generated_files:
                z.write(fpath, f"{key}.wav")
        size_kb = os.path.getsize(zip_path) // 1024
        print(f"  Wrote {zip_path} ({size_kb} KB)")

def main():
    for cfg in PACK_CONFIGS:
        zip1 = os.path.join("app", "src", "main", "assets", "voice", f"{cfg['id']}.zip")
        zip2 = os.path.join("assets", "voice", f"{cfg['id']}.zip")
        generate_pack(cfg, [zip1, zip2])
    print("All voice packs successfully generated with distinct voices!")

if __name__ == "__main__":
    main()
