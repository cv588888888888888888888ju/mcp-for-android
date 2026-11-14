import asyncio, json, sys

URL = "http://127.0.0.1:9500/mcp"

async def main():
    from mcp import ClientSession
    from mcp.client.streamable_http import streamable_http_client
    async with streamable_http_client(URL) as conn:
        try:
            read, write, _sid = conn
        except Exception:
            read, write = conn
        async with ClientSession(read, write) as s:
            await asyncio.wait_for(s.initialize(), 15)
            # argv: name args-json [name2 args2-json ...]
            i = 1
            while i < len(sys.argv):
                name = sys.argv[i]
                args = json.loads(sys.argv[i+1]) if i+1 < len(sys.argv) else {}
                i += 2
                print(f"\n===== {name} =====", flush=True)
                res = await asyncio.wait_for(s.call_tool(name, args), 90)
                for c in res.content:
                    text = getattr(c, "text", str(c))
                    print(text[:9000], flush=True)
                if res.isError:
                    print("[ERROR]", flush=True)

asyncio.run(main())
