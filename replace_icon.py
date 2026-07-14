import os
from PIL import Image

SOURCE_ICON = r'D:\download\ChatGPT Image 2026年7月13日 18_32_21.png'
RES_DIR = r'D:\dev\bolan\app\src\main\res'

MIPMAP_SIZES = {
    'mipmap-mdpi': 48,
    'mipmap-hdpi': 72,
    'mipmap-xhdpi': 96,
    'mipmap-xxhdpi': 144,
    'mipmap-xxxhdpi': 192,
}

def resize_and_save(img, size, output_path):
    resized = img.resize((size, size), Image.LANCZOS)
    if resized.mode != 'RGBA':
        resized = resized.convert('RGBA')
    resized.save(output_path, 'PNG', optimize=True)
    print(f'已生成: {output_path} ({os.path.getsize(output_path)} bytes)')

def create_foreground_icon(img, size):
    resized = img.resize((size, size), Image.LANCZOS)
    if resized.mode != 'RGBA':
        resized = resized.convert('RGBA')
    
    mask = Image.new('L', (size, size), 0)
    draw = None
    try:
        from PIL import ImageDraw
        draw = ImageDraw.Draw(mask)
        draw.ellipse((0, 0, size, size), fill=255)
    except:
        pass
    
    if draw:
        result = Image.new('RGBA', (size, size), (0, 0, 0, 0))
        result.paste(resized, (0, 0), mask)
    else:
        result = resized
    
    return result

def main():
    if not os.path.exists(SOURCE_ICON):
        print(f'错误: 源图标不存在 - {SOURCE_ICON}')
        return
    
    img = Image.open(SOURCE_ICON)
    print(f'源图标: {img.size} {img.mode}')
    
    for mipmap, size in MIPMAP_SIZES.items():
        mipmap_dir = os.path.join(RES_DIR, mipmap)
        if not os.path.exists(mipmap_dir):
            os.makedirs(mipmap_dir)
        
        launcher_path = os.path.join(mipmap_dir, 'ic_launcher.png')
        round_path = os.path.join(mipmap_dir, 'ic_launcher_round.png')
        foreground_path = os.path.join(mipmap_dir, 'ic_launcher_foreground.png')
        
        resize_and_save(img, size, launcher_path)
        resize_and_save(img, size, round_path)
        
        foreground = create_foreground_icon(img, size)
        foreground.save(foreground_path, 'PNG', optimize=True)
        print(f'已生成: {foreground_path} ({os.path.getsize(foreground_path)} bytes)')
    
    print('\n图标替换完成！')

if __name__ == '__main__':
    main()