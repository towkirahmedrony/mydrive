// Temporary verification function - REMOVED.
// Neutralised endpoint: it holds no logic, reads no credentials and returns 410.
Deno.serve(() => new Response(JSON.stringify({ error: "This temporary diagnostic endpoint has been removed." }), {
  status: 410,
  headers: { "Content-Type": "application/json" },
}));
