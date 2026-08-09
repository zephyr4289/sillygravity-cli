import json
import os
import subprocess
import requests
from prompt_toolkit import PromptSession
from prompt_toolkit.key_binding import KeyBindings
from prompt_toolkit.history import FileHistory
from rich.console import Console
from rich.live import Live
from rich.markdown import Markdown

API_URL = "http://127.0.0.1:8080/v1/chat/completions"
console = Console()

tools = [
    {
        "type": "function",
        "function": {
            "name": "run_shell_command",
            "description": "Run a shell command",
            "parameters": {
                "type": "object",
                "properties": {"command": {"type": "string"}},
                "required": ["command"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "read_file",
            "description": "Read file contents",
            "parameters": {
                "type": "object",
                "properties": {"path": {"type": "string"}},
                "required": ["path"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "patch_file",
            "description": "Search and replace block in a file perfectly matching search content.",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string"},
                    "search": {"type": "string"},
                    "replace": {"type": "string"}
                },
                "required": ["path", "search", "replace"]
            }
        }
    }
]

def apply_patch(path, search, replace):
    with open(path, "r") as f: content = f.read()
    if search not in content: return f"Error: Search block not found perfectly in {path}"
    with open(path, "w") as f: f.write(content.replace(search, replace))
    return "Patch applied successfully."

def run_shell(command):
    res = subprocess.run(command, shell=True, capture_output=True, text=True)
    return res.stdout + res.stderr

def stream_llm(messages):
    payload = {"model": "qwen2.5", "messages": messages, "stream": True, "tools": tools}
    response = ""
    tool_calls = None

    try:
        req = requests.post(API_URL, json=payload, stream=True, timeout=30)
        with Live(Markdown(""), refresh_per_second=10) as live:
            for line in req.iter_lines():
                if line:
                    line = line.decode('utf-8').replace('data: ', '')
                    if line == '[DONE]': break
                    try:
                        data = json.loads(line)
                        delta = data['choices'][0]['delta']
                        if 'content' in delta and delta['content']:
                            response += delta['content']
                            live.update(Markdown(response))
                        if 'tool_calls' in delta:
                            tool_calls = delta['tool_calls']
                    except: pass
    except requests.exceptions.RequestException:
        console.print("[red]API Connection failed. Ensure the Android app is running.[/red]")
    return response, tool_calls

def approval_gate(tool_name, args):
    console.print(f"\n[bold yellow]Agent wants to execute: {tool_name}[/bold yellow]")
    console.print(f"[dim]{json.dumps(args, indent=2)}[/dim]")
    choice = input("[Y]es / [N]o / [E]dit: ").strip().lower()
    
    if choice == 'y': return args, None
    elif choice == 'e':
        import tempfile
        with tempfile.NamedTemporaryFile(mode='w+', suffix='.json', delete=False) as f:
            json.dump(args, f, indent=2)
            tmp_name = f.name
        subprocess.run(['nano', tmp_name])
        with open(tmp_name, 'r') as f:
            edited_args = json.load(f)
        os.unlink(tmp_name)
        return edited_args, None
    else:
        reason = input("Reason for rejection: ")
        return None, reason

def main():
    bindings = KeyBindings()
    @bindings.add('enter')
    def _(event): event.current_buffer.insert_text('\n')
    @bindings.add('escape', 'enter')
    def _(event): event.current_buffer.validate_and_handle()

    session = PromptSession(history=FileHistory(os.path.expanduser('~/.agent_history')), key_bindings=bindings)
    messages = [{"role": "system", "content": "You are a fast Termux CLI agent. Use tools appropriately."}]

    console.print("[bold green]Agent CLI Started. (Alt+Enter to submit)[/bold green]")
    while True:
        try:
            user_input = session.prompt("\nUser: ", multiline=True)
            messages.append({"role": "user", "content": user_input})
            
            while True:
                content, tools_req = stream_llm(messages)
                if content: messages.append({"role": "assistant", "content": content})
                
                if not tools_req: break
                
                for tc in tools_req:
                    func = tc['function']
                    name = func['name']
                    args = json.loads(func['arguments'])
                    
                    approved_args, rejection = approval_gate(name, args)
                    if rejection:
                        res = f"User rejected execution. Reason: {rejection}"
                    else:
                        if name == 'run_shell_command': res = run_shell(approved_args['command'])
                        elif name == 'read_file':
                            try:
                                with open(approved_args['path']) as f: res = f.read()
                            except Exception as e: res = str(e)
                        elif name == 'patch_file': res = apply_patch(approved_args['path'], approved_args['search'], approved_args['replace'])
                    
                    messages.append({"role": "tool", "name": name, "content": str(res)})
                    
        except (KeyboardInterrupt, EOFError): break

if __name__ == "__main__": main()
