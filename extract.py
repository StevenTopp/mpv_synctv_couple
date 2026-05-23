import json
import os

def clean_arg(v):
    if isinstance(v, str) and v.startswith('"') and v.endswith('"'):
        try: return json.loads(v)
        except: return v
    return v

def search_conversations():
    conversations = ['686ee96a-b231-470b-882e-f7a112529741', 'c39c1186-b673-4cad-9d83-c9fc6b7d81b6', '241bac99-15c7-4f81-8144-498f66834d03']
    for cid in conversations:
        transcript_path = fr'C:\Users\Steven\.gemini\antigravity-ide\brain\{cid}\.system_generated\logs\transcript.jsonl'
        if not os.path.exists(transcript_path): continue
        with open(transcript_path, 'r', encoding='utf-8') as f:
            for line in f:
                try:
                    data = json.loads(line)
                    if 'tool_calls' in data:
                        for tc in data['tool_calls']:
                            if tc.get('name') in ['replace_file_content', 'multi_replace_file_content', 'write_to_file']:
                                args = tc.get('args', {})
                                target = clean_arg(args.get('TargetFile', ''))
                                if 'MPVActivity.kt' in target:
                                    print(f'\n=== Found MPVActivity.kt edit in {cid} ===')
                                    if 'ReplacementContent' in args:
                                        content = clean_arg(args['ReplacementContent'])
                                        print(content[:300])
                                        with open('recovered_mpvactivity.txt', 'a', encoding='utf-8') as out:
                                            out.write(content + '\n\n=====\n\n')
                                    elif 'ReplacementChunks' in args:
                                        chunks = clean_arg(args['ReplacementChunks'])
                                        if isinstance(chunks, str):
                                            try: chunks = json.loads(chunks)
                                            except: pass
                                        if isinstance(chunks, list):
                                            for chunk in chunks:
                                                content = chunk.get('ReplacementContent', '')
                                                print(content[:300])
                                                with open('recovered_mpvactivity.txt', 'a', encoding='utf-8') as out:
                                                    out.write(content + '\n\n=====\n\n')
                                    elif 'CodeContent' in args:
                                        content = clean_arg(args['CodeContent'])
                                        print(content[:300])
                                        with open('recovered_mpvactivity.txt', 'a', encoding='utf-8') as out:
                                            out.write(content + '\n\n=====\n\n')
                except: pass

search_conversations()
