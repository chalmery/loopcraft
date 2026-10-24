export function response(data: unknown) {
  return Response.json({ code: 0, message: 'success', data })
}
