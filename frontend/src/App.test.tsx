import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'

/** A fake backend: one document, and a chat answer streamed as server-sent events in small pieces. */
function fakeBackend() {
  return vi.fn(async (url: string, init?: RequestInit) => {
    if (url === '/api/documents' && !init?.method) {
      return Response.json([
        { id: 'd1', filename: 'handbook.md', contentType: 'text/markdown', sizeBytes: 4919, chunkCount: 11, createdAt: '' },
      ])
    }
    if (url === '/api/chat') {
      const events = [
        'event:sources\ndata:{"sources":[{"index":1,"documentId":"d1","filename":"handbook.md","title":"Handbook","chunkIndex":4,"score":0.71,"text":"Full-time employees receive 27 days of paid vacation."}]}\n\n',
        'event:token\ndata:{"text":"You get 27 days"}\n\n',
        'event:token\ndata:{"text":" [1]."}\n\n',
        'event:done\ndata:{"citedSources":[1],"notFound":false,"promptTokens":700,"completionTokens":9,"durationMs":1500}\n\n',
      ]
      const body = new ReadableStream<Uint8Array>({
        start(controller) {
          const encoder = new TextEncoder()
          events.forEach((event) => controller.enqueue(encoder.encode(event)))
          controller.close()
        },
      })
      return new Response(body, { headers: { 'Content-Type': 'text/event-stream' } })
    }
    return new Response(null, { status: 404 })
  })
}

beforeEach(() => {
  vi.stubGlobal('fetch', fakeBackend())
  Element.prototype.scrollIntoView = vi.fn() // not implemented by jsdom
})
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('App', () => {
  it('lists documents, streams an answer and opens the cited source', async () => {
    render(<App />)
    expect(await screen.findByText('handbook.md')).toBeTruthy()

    fireEvent.change(screen.getByLabelText('Question'), { target: { value: 'How many vacation days?' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))

    expect(await screen.findByText(/You get 27 days/)).toBeTruthy()
    await waitFor(() => expect(screen.getByText('1.5 s')).toBeTruthy())

    fireEvent.click(screen.getByRole('button', { name: 'Source 1: Handbook' }))

    const panel = screen.getByRole('complementary', { name: 'Source' })
    expect(panel.textContent).toContain('Full-time employees receive 27 days of paid vacation.')
    expect(panel.textContent).toContain('Handbook')
    expect(panel.textContent).toContain('handbook.md · part 5 · 71% match')

    fireEvent.keyDown(window, { key: 'Escape' })
    expect(screen.queryByRole('complementary', { name: 'Source' })).toBeNull()
  })

  it('explains an unreachable backend and recovers when it comes back', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const backend = fakeBackend()
    let down = true
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) =>
        down ? Promise.resolve(new Response('<html>502 Bad Gateway</html>', { status: 502 })) : backend(url, init),
      ),
    )
    try {
      render(<App />)

      expect((await screen.findByRole('alert')).textContent).toContain("Can't reach the backend")
      expect(screen.getByText('Retrying automatically…')).toBeTruthy()

      down = false
      await vi.advanceTimersByTimeAsync(5000)

      expect(await screen.findByText('handbook.md')).toBeTruthy()
      expect(screen.queryByRole('alert')).toBeNull()
    } finally {
      vi.useRealTimers()
    }
  })

  it('sends earlier exchanges with a follow-up question', async () => {
    render(<App />)
    const question = screen.getByLabelText('Question')

    fireEvent.change(question, { target: { value: 'How many vacation days?' } })
    fireEvent.keyDown(question, { key: 'Enter' })
    await waitFor(() => expect(screen.getByText('1.5 s')).toBeTruthy())

    fireEvent.change(question, { target: { value: 'Are you sure?' } })
    fireEvent.keyDown(question, { key: 'Enter' })
    await waitFor(() => expect(screen.getAllByText('1.5 s')).toHaveLength(2))

    const chatCalls = vi.mocked(fetch).mock.calls.filter(([url]) => url === '/api/chat')
    expect(JSON.parse(chatCalls[0][1]!.body as string).history).toEqual([])
    expect(JSON.parse(chatCalls[1][1]!.body as string)).toEqual({
      question: 'Are you sure?',
      history: [{ question: 'How many vacation days?', answer: 'You get 27 days [1].' }],
    })
  })

  it('suggests questions about the uploaded documents and asks one when clicked', async () => {
    render(<App />)
    const suggestion = await screen.findByRole('button', { name: 'What is the Handbook document about?' })

    fireEvent.click(suggestion)

    expect(await screen.findByText(/You get 27 days/)).toBeTruthy()
    const chatCall = vi.mocked(fetch).mock.calls.find(([url]) => url === '/api/chat')!
    expect(JSON.parse(chatCall[1]!.body as string).question).toBe('What is the Handbook document about?')
  })

  it('copies an answer without its citation markers', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } })
    render(<App />)
    fireEvent.change(screen.getByLabelText('Question'), { target: { value: 'Vacation?' } })
    fireEvent.keyDown(screen.getByLabelText('Question'), { key: 'Enter' })

    fireEvent.click(await screen.findByRole('button', { name: 'Copy' }))

    await waitFor(() => expect(writeText).toHaveBeenCalledWith('You get 27 days.'))
    expect(await screen.findByRole('button', { name: 'Copied' })).toBeTruthy()
  })

  it('sends the question as JSON to the chat endpoint', async () => {
    render(<App />)
    fireEvent.change(screen.getByLabelText('Question'), { target: { value: '  Vacation?  ' } })
    fireEvent.keyDown(screen.getByLabelText('Question'), { key: 'Enter' })

    await screen.findByText(/You get 27 days/)
    const chatCall = vi.mocked(fetch).mock.calls.find(([url]) => url === '/api/chat')!
    expect(JSON.parse(chatCall[1]!.body as string)).toEqual({ question: 'Vacation?', history: [] })
  })
})
