import { afterEach, describe, expect, it, vi } from 'vitest'
import { BACKEND_UNAVAILABLE, listDocuments, toError } from './api'

afterEach(() => vi.unstubAllGlobals())

describe('toError', () => {
  it("uses the backend's problem detail", async () => {
    const error = await toError(
      Response.json({ status: 415, detail: "File type 'exe' is not supported." }, { status: 415 }),
    )

    expect(error.status).toBe(415)
    expect(error.message).toBe("File type 'exe' is not supported.")
  })

  it('explains a proxy error page as the backend being unreachable', async () => {
    const error = await toError(new Response('<html>502 Bad Gateway</html>', { status: 502 }))

    expect(error.message).toBe(BACKEND_UNAVAILABLE)
  })

  it("keeps the backend's own 500 detail", async () => {
    const error = await toError(Response.json({ status: 500, detail: 'Database is read-only' }, { status: 500 }))

    expect(error.message).toBe('Database is read-only')
  })

  it('falls back to the status code for other errors without details', async () => {
    expect((await toError(new Response('', { status: 404 }))).message).toBe('Request failed (404)')
  })
})

describe('requests', () => {
  it('reports a network failure as the backend being unreachable', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))

    await expect(listDocuments()).rejects.toThrow(BACKEND_UNAVAILABLE)
  })
})
