import test from "node:test";
import assert from "node:assert/strict";
import { parseAdminPost } from "./index.ts";

test("parses one-line Telegram ADD posts", () => {
  assert.deepEqual(
    parseAdminPost("ADD unified | Unified Add-on | https://addons.example/api"),
    {
      kind: "add",
      id: "unified",
      name: "Unified Add-on",
      baseUrl: "https://addons.example/api",
    },
  );
});

test("parses multiline Telegram ADD posts", () => {
  assert.deepEqual(
    parseAdminPost("ADD audio_one\nAudio One\nhttps://audio.example"),
    {
      kind: "add",
      id: "audio_one",
      name: "Audio One",
      baseUrl: "https://audio.example",
    },
  );
});

test("parses removals and rejects unsafe or malformed entries", () => {
  assert.deepEqual(parseAdminPost("REMOVE audio_one"), { kind: "remove", id: "audio_one" });
  assert.equal(parseAdminPost("ADD bad id\nName\nhttps://audio.example"), null);
  assert.equal(parseAdminPost("ADD audio\nName\nhttp://audio.example"), null);
  assert.equal(parseAdminPost("ADD audio\nName\nhttps://localhost:8000"), null);
});
