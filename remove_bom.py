import os

def remove_bom(filepath):
    with open(filepath, 'rb') as f:
        content = f.read()
    if content.startswith(b'\xef\xbb\xbf'):
        content = content[3:]
        with open(filepath, 'wb') as f:
            f.write(content)
        print(f'Removed BOM: {filepath}')

for root, dirs, files in os.walk('app/src/main/java'):
    for file in files:
        if file.endswith('.java') or file.endswith('.kt'):
            remove_bom(os.path.join(root, file))