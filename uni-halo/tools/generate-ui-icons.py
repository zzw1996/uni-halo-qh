"""从 lucide-static 官方字体生成小程序 PNG。依赖 Pillow，参数为包目录。"""
import json, shutil, sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
source=Path(sys.argv[1]); root=Path(__file__).resolve().parents[1]
out=root/'static/ui-icons'; out.mkdir(parents=True,exist_ok=True)
codes=json.loads((source/'font/codepoints.json').read_text())
font=ImageFont.truetype(str(source/'font/lucide.ttf'),80)
icons={'sparkles':'green','play':'blue','crown':'amber','gift':'coral','user-round-plus':'green','message-circle':'blue','history':'amber','heart':'coral','file-pen-line':'green','receipt-text':'blue','book-open-check':'green','settings-2':'blue','chart-no-axes-combined':'blue','archive':'amber','link':'green','shield-check':'blue','headset':'green','message-square-text':'blue','info':'blue','house':'green','compass':'green','images':'green','user-round':'green','shopping-bag':'green','circle-check':'green'}
light={'green':'#176b5b','blue':'#3874a3','amber':'#a76d24','coral':'#b65b58','muted':'#65736d','active':'#176b5b'}
dark={'green':'#91cdb8','blue':'#9ec2ed','amber':'#e2bd81','coral':'#eaa3a0','muted':'#9caaa4','active':'#9db0ff'}
def draw(name,color,path):
    im=Image.new('RGBA',(96,96)); d=ImageDraw.Draw(im); c=chr(codes[name]); box=d.textbbox((0,0),c,font=font)
    d.text(((96-box[2]+box[0])/2-box[0],(96-box[3]+box[1])/2-box[1]),c,font=font,fill=color)
    im.quantize(colors=32,method=Image.Quantize.FASTOCTREE).save(path,optimize=True)
for name,tone in icons.items():
    for suffix,colors in [('',light),('--dark',dark)]:draw(name,colors[tone],out/(name+suffix+'.png'))
nav={'home':'house','category':'compass','gallery':'images','rank':'chart-no-axes-combined','mine':'user-round','store':'shopping-bag'}
for key,name in nav.items():
    for darkmode,colors in [(False,light),(True,dark)]:
        for active in [False,True]:
            suffix=('--dark' if darkmode else '')+('--active' if active else '--muted')
            draw(name,colors['active' if active else 'muted'],out/(name+suffix+'.png'))
            if key == 'store': continue
            shutil.copyfile(out/(name+suffix+'.png'),root/'static/tabbar'/f"{'ink' if darkmode else 'paper'}_{key}{'_active' if active else ''}.png")
shutil.copyfile(source/'LICENSE',out/'LICENSE.txt')
(out/'README.md').write_text('Lucide 1.56.0，ISC 许可。只打包选用图标，PNG 96×96。\n生成命令：python3 tools/generate-ui-icons.py <lucide-static 包目录>\n')
print(f'Generated {len(list(out.glob("*.png")))} theme-aware icons')

# UI theme variants are rendered from the original vector font, not recolored bitmaps.
ui_themes={
 'sky':{'green':'#0874d5','blue':'#168bfa','amber':'#b76b0b','coral':'#d87057','muted':'#647a91','active':'#0874d5'},
 'mint':{'green':'#557861','blue':'#687e80','amber':'#99723d','coral':'#a83e32','muted':'#7c8174','active':'#a83e32'},
 'ink':{'green':'#56e3ef','blue':'#91b8ff','amber':'#dfbc76','coral':'#eca1a8','muted':'#9bafcc','active':'#56e3ef'},
 'sunrise':{'green':'#6b8863','blue':'#6b8b98','amber':'#ab793e','coral':'#bc6745','muted':'#8f7864','active':'#a34f31'}
}
for theme,colors in ui_themes.items():
 for name,tone in icons.items():draw(name,colors[tone],out/(name+'--'+theme+'.png'))
 for name in nav.values():
  for state in ['active','muted']:draw(name,colors[state],out/(name+'--'+theme+'--'+state+'.png'))
