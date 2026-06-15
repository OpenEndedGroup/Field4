clearAFrame()

var num = 200
var droplets = []

for(var i=0;i<num;i++)
{
    droplets.push(vec(Math.random(),Math.random(),Math.random()-1))
}

var rainDirection = vec(0,-0.05,0)

_r = () => {
	var f = new FLine()
	for(var i=0;i<num;i++)
	{
		f.moveTo(droplets[i])
		f.lineTo(droplets[i]+rainDirection)
	}
    f.color = vec(1,0,1,1)
	stage.f = f

	for(var i=0;i<num;i++)
	{
		droplets[i] = droplets[i] + rainDirection
        if (droplets[i].y<0)
        {
            droplets[i].y = 2
        }
	}
}