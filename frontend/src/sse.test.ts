import { describe, expect, it } from 'vitest'
import { parseSse, type SseMessage } from './sse'

function streamOf(...chunks: (string | Uint8Array)[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder()
  return new ReadableStream({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(typeof chunk === 'string' ? encoder.encode(chunk) : chunk)
      controller.close()
    },
  })
}

async function collect(stream: ReadableStream<Uint8Array>): Promise<SseMessage[]> {
  const messages: SseMessage[] = []
  for await (const message of parseSse(stream)) messages.push(message)
  return messages
}

describe('parseSse', () => {
  it('parses named events in order', async () => {
    const messages = await collect(streamOf('event:sources\ndata:{"a":1}\n\nevent:token\ndata:{"text":"hi"}\n\n'))

    expect(messages).toEqual([
      { event: 'sources', data: '{"a":1}' },
      { event: 'token', data: '{"text":"hi"}' },
    ])
  })

  it('reassembles events split across network chunks', async () => {
    const messages = await collect(streamOf('event:tok', 'en\nda', 'ta:{"text":"a"}\n', '\nevent:done\ndata:{}\n\n'))

    expect(messages).toEqual([
      { event: 'token', data: '{"text":"a"}' },
      { event: 'done', data: '{}' },
    ])
  })

  it('decodes a multi-byte character split between chunks', async () => {
    const bytes = new TextEncoder().encode('data:{"text":"27 € café"}\n\n')
    const euro = new TextEncoder().encode('data:{"text":"27 ').length + 1 // split inside the 3-byte €

    const messages = await collect(streamOf(bytes.slice(0, euro), bytes.slice(euro)))

    expect(messages).toEqual([{ event: 'message', data: '{"text":"27 € café"}' }])
  })

  it('joins multi-line data and ignores comments and optional spaces', async () => {
    const messages = await collect(streamOf(': keep-alive\nevent: token\ndata: line one\ndata: line two\n\n'))

    expect(messages).toEqual([{ event: 'token', data: 'line one\nline two' }])
  })

  it('handles CRLF line endings and a final event without a trailing blank line', async () => {
    const messages = await collect(streamOf('event:a\r\ndata:1\r\n\r\nevent:b\r\ndata:2'))

    expect(messages).toEqual([
      { event: 'a', data: '1' },
      { event: 'b', data: '2' },
    ])
  })
})
