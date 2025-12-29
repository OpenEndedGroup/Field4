# Slideshow Feature - Quick Start Guide

## Installation

The slideshow feature is now built into Field4. No additional installation is required.

## Creating Your First Slideshow

### Step 1: Create Some Boxes

Create a few boxes in your Field document with content you want to showcase.

### Step 2: Create a Guide Box

Create a new box that will contain the slideshow script. Add this code:

```kotlin
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

// Option 1: Get references to your boxes
val box1 = root.children.first { it.name == "Box 1" }
val box2 = root.children.first { it.name == "Box 2" }

// Create the slideshow
val tour = slideShow {
    step("Welcome") {
        note("""
            <h2>Welcome to My Field Document!</h2>
            <p>This tour will show you the key features.</p>
            <p><a href="#slideshow:next">Start Tour →</a></p>
        """)
        selectBox(this@slideShow)
    }
    
    step("First Feature") {
        note("""
            <h3>Check out this code</h3>
            <p>This box demonstrates feature X.</p>
            <p><a href="#slideshow:previous">← Back</a> | 
               <a href="#slideshow:next">Next →</a></p>
        """)
        // Option 1: Use box reference
        selectBox(box1, property = "code", line = 1)
    }
    
    step("Second Feature") {
        note("""
            <h3>Look at line 10</h3>
            <p>This is where the magic happens!</p>
            <p><a href="#slideshow:previous">← Back</a> | 
               <a href="#slideshow:stop">Finish</a></p>
        """)
        // Option 2: Use box name (searches recursively)
        // This also works with nested boxes - disclosure areas will open automatically!
        selectBoxByName("Box 2", property = "code", line = 10, highlight = 10)
    }
}

// Start the tour automatically
GlobalScope.launch {
    tour.start()
}

// Or add controls to the box actions menu
this.installSlideShowControls()
```

### Step 3: Run the Code

Execute the guide box code. The slideshow will start automatically!

## Navigation

Users can navigate through your slideshow by:

1. **Clicking links in notes**: `<a href="#slideshow:next">Next</a>`
2. **Using menu actions**: If you called `installSlideShowControls()`
3. **Programmatically**: Via the slideshow API in code

## Common Patterns

### Pattern 1: Linear Tutorial

```kotlin
slideShow {
    step("Step 1") { /* ... */ }
    step("Step 2") { /* ... */ }
    step("Step 3") { /* ... */ }
}
```

### Pattern 2: Choose Your Own Path

```kotlin
slideShow {
    step("Introduction") {
        note("""
            <h2>What do you want to learn?</h2>
            <p><a href="#slideshow:1">Graphics</a></p>
            <p><a href="#slideshow:5">Data Processing</a></p>
        """)
    }
    
    step("Graphics - Part 1") { /* ... */ }
    step("Graphics - Part 2") { /* ... */ }
    // ...
    step("Data - Part 1") { /* ... */ }
    step("Data - Part 2") { /* ... */ }
}
```

### Pattern 3: Interactive Steps with Callbacks

```kotlin
slideShow {
    step("Setup") {
        note("Let's initialize some variables...")
        selectBox(setupBox)
        
        onEnter {
            // Run setup code automatically
            setupBox.begin()
        }
    }
    
    step("Results") {
        note("Here are the results!")
        selectBox(resultsBox)
        
        onEnter {
            // Refresh results
            resultsBox.begin()
        }
    }
}
```

## Tips

1. **Keep it Simple**: Start with 3-5 steps
2. **Test Your Tour**: Run through it as a new user would
3. **Use Clear Language**: Avoid jargon
4. **Highlight Key Lines**: Use the `highlight` parameter
5. **Provide Context**: Explain what users are seeing

## Example HTML for Notes

```html
<h2>Main Title</h2>
<h3>Subtitle</h3>

<p>Regular paragraph with <strong>bold</strong> and <em>italic</em>.</p>

<ul>
  <li>Bullet point 1</li>
  <li>Bullet point 2</li>
</ul>

<p>Press <kbd>Cmd+Enter</kbd> to execute code.</p>

<p>
  <a href="#slideshow:next">Next →</a>
  <a href="#slideshow:previous">← Back</a>
</p>
```

## Troubleshooting

**Q: My notes don't appear**  
A: Check the browser console for errors. Make sure your HTML is well-formed.

**Q: Box selection doesn't work**  
A: Verify the box names/UIDs are correct. Use `root.children.forEach { println(it.name) }` to list all boxes.

**Q: Line scrolling isn't working**  
A: Make sure the line number exists in the code and the Monaco editor is loaded.

## Next Steps

- See `SLIDESHOW_FEATURE.md` for complete documentation
- Check `slideshow_example.kt` for more advanced examples
- Experiment with the DSL to create your own tours!

## Support

For issues or questions:
1. Check the documentation in `SLIDESHOW_FEATURE.md`
2. Look at the examples in `slideshow_example.kt`
3. Review the code in `SlideShow.kt`
