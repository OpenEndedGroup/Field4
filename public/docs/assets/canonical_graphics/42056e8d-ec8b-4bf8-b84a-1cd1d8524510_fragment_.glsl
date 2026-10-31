#version 450
layout(location = 0) out vec4 uFragColor;
layout(location = 0) in vec4 fcol;

// Generated from the Kotlin class MyPC: the push_constant block, loadRegs() and accessors a(), b(), c().
{{ pc.dec }}
void main()
{
    loadRegs();

    uFragColor = vec4(a());
}
