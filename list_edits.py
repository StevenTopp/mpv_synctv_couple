import json
import os

conversations = ['686ee96a-b231-470b-882e-f7a112529741', 'c39c1186-b673-4cad-9d83-c9fc6b7d81b6', '241bac99-15c7-4f81-8144-498f66834d03']
for cid in conversations:
    transcript_path = fr'C:\Users\Steven\.gemini\antigravity-ide\brain\{cid}\.system_generated\logs\transcript.jsonl'
    if not os.path.exists(transcript_path): continue
    try:
        with open(transcript_path, 'r', encoding='utf-8') as f:
            for line in f:
                data = json.loads(line)
                if 'tool_calls' in data:
                    for tc in data['tool_calls']:
                        func = tc.get('function', {}).get('name')
                        if func in ['replace_file_content', 'multi_replace_file_content', 'write_to_file']:
                            args = tc.get('function', {}).get('arguments', {})
                            if isinstance(args, str):
                                try:
                                    args = json.loads(args)
                                except: pass
                            if isinstance(args, dict):
                                target = args.get('TargetFile', '')
                                if target: print(f'[{cid}] Edited: {target}')
    except Exception as e:
        print('Error:', e)
