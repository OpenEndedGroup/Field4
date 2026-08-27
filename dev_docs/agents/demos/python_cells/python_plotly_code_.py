# plotly's default renderer starts a one-shot local server and asks the OS to open a
# browser. Field intercepts that and embeds the page in this box's "browser" channel.
import numpy as np
import plotly.graph_objects as go

t = np.linspace(0, 4 * np.pi, 400)
fig = go.Figure(go.Scatter(x=t, y=np.sin(t) * np.exp(-t / 10), mode="lines"))
fig.update_layout(template="plotly_dark", margin=dict(l=55, r=20, t=25, b=40))
fig.show()
