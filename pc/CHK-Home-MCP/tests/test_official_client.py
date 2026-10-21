"""Real MCP SDK interoperability smoke test (not the server's own JSON client).

The actual official MCP Python client conducts the streaming HTTP handshake,
initialization and tool discovery over HTTP. No public IP or private key used.
"""
import asyncio
import tempfile
import threading
import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import chk_home_mcp as mcp
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client


class OfficialMcpClientTest(unittest.TestCase):
    def test_streamable_http_initialize_and_tools(self):
        with tempfile.TemporaryDirectory() as temp:
            server = mcp.LocalMcpServer(("127.0.0.1", 0), mcp.Handler)
            server.cfg = {"root": Path(temp).resolve(), "secret": "8" * 64}
            server.daemon_threads = True
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            endpoint = f"http://127.0.0.1:{server.server_port}/mcp/" + ("8" * 64)

            async def run_client():
                async with streamable_http_client(endpoint) as (read, write, _session):
                    async with ClientSession(read, write) as client:
                        result = await client.initialize()
                        self.assertEqual(result.serverInfo.name, "CHK Home MCP")
                        tools = await client.list_tools()
                        self.assertEqual(len(tools.tools), 10)
                        one = await client.call_tool("server_info", {})
                        self.assertFalse(one.isError)
                        self.assertTrue(one.content)

            try:
                asyncio.run(asyncio.wait_for(run_client(), timeout=15))
            finally:
                server.shutdown()
                server.server_close()
                thread.join(3)


if __name__ == "__main__":
    unittest.main()
