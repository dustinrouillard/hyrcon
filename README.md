# Hytale HyRCON Mod

This mod runs a TCP server that listens for commands from external HyRCON clients to run in the server console, allowing easier commands in headless containerized deployments.

## Environment Variables

- `HYRCON_ENABLED` / `RCON_ENABLED`: Enables or disables the HyRCON server. Defaults to `true`.
- `HYRCON_BIND` / `RCON_BIND`: Optional combined bind address in `host:port` (or `[ipv6]:port`) form. Defaults to `0.0.0.0:5522`.
- `HYRCON_HOST` / `RCON_HOST`: Host fallback when no bind address is set. Defaults to `0.0.0.0`.
- `HYRCON_PORT` / `RCON_PORT`: Port fallback when no bind address is set. Defaults to `5522`.
- `HYRCON_PASSWORD` / `RCON_PASSWORD`: Password required for client authentication. Defaults to `changeme`.

## Connecting to the HyRCON Server

We've created a simple Rust client that can be used to connect to the HyRCON server, which you can download from [here](https://github.com/dustinrouillard/hyrcon-client/releases).

## Running your server in Docker

If you're looking for an easy way to run your server in Docker, I also created a container image that handles OAuth and automatic mod downloads which includes this mod for its in-built RCON capabilities, you can find that over at [dustinrouillard/hytale-docker](https://github.com/dustinrouillard/hytale-docker)
