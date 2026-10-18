## Overview
Create a Java application using Spring boot, built with Gradle. This application will be responsible for downloading
an image from pages on NASA Astronomy Picture of the Day, which is at [url](https://science.nasa.gov/apod/). 
This site has one page for every day going back many years. The previous days are available at unique URLs. 
An example of that URL is [June 24th, 2019](https://science.nasa.gov/image-article/apod-2019-june-24-anticrepuscular-rays-converge-opposite-the-sun/).
Unfortunately, the URLs have the "page title" in the name, and so cannot be paginated without knowing it. 


This application should keep track of what days it has already downloaded - their date and URL - and save and load from a CSV file. 

When the application is started, it should pick a "starting day" based on the last known day. 

Then, it needs to fetch that page, and parse the HTML to find the URL for the "previous day's page". This URL should be in an 
HREF element with classes "smd-next-prev__prev". 

It should then fetch that page, and look for the image that is the "main image of the page". This should be an image nested inside a DIV element,
with classes "media-detail-hero__media". The image usually comes from https://assets.science.nasa.gov. The image should be wrapped in an HREF that goes to 
the larger version of the image, and it is that larger version that we want. 

Having identified the larger image version, the application should download it. After that is succesful, it should save the day and page URL
to the "visited" database, and repeat the process by looking for the href of the "previous day's page". 

The images should be saved in a "downloadedImages" folder, with their same filename. 

Sometimes a movie is shown on a day instead of an image. On these days, the application should skip the download process and move to the next "previous day".

There are two main operations involved in the above: processing a page for the target image URL and next page target (the next "previous day's image"),
and downloading the target image. The target image will usually be large in file size, whereas the HTML contents are small in comparison, so we can
speed up the application by splitting out the target image download to a separate queue-driven, pub/sub model with a configurable worker thread count.

The application should ideally have a way to gracefully shut down, and then safely resume again. 

