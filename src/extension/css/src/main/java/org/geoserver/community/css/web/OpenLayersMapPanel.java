/* (c) 2014 - 2016 Open Source Geospatial Foundation - all rights reserved
 * (c) 2001 - 2013 OpenPlans
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.community.css.web;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.io.IOUtils;
import org.apache.wicket.Component;
import org.apache.wicket.WicketRuntimeException;
import org.apache.wicket.markup.head.CssHeaderItem;
import org.apache.wicket.markup.head.IHeaderResponse;
import org.apache.wicket.markup.head.JavaScriptHeaderItem;
import org.apache.wicket.markup.head.OnLoadHeaderItem;
import org.apache.wicket.markup.html.IHeaderContributor;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.panel.Panel;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.catalog.ResourceInfo;
import org.geoserver.catalog.StyleInfo;
import org.geoserver.config.GeoServerDataDirectory;
import org.geoserver.ows.URLMangler.URLType;
import org.geoserver.ows.util.ResponseUtils;
import org.geoserver.platform.resource.Resource;
import org.geoserver.platform.resource.Resources;
import org.geoserver.web.GeoServerApplication;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.util.logging.Logging;

import freemarker.template.Configuration;
import freemarker.template.DefaultObjectWrapper;
import freemarker.template.Template;
import freemarker.template.TemplateException;

class OpenLayersMapPanel extends Panel implements IHeaderContributor {
    private static final long serialVersionUID = 3484477911061256642L;
    static final Logger LOGGER  = Logging.getLogger(OpenLayersMapPanel.class);
    final static Configuration templates;
    
    static {
        templates = new Configuration();
        templates.setClassForTemplateLoading(OpenLayersMapPanel.class, "");
        templates.setObjectWrapper(new DefaultObjectWrapper());
    }

    final Random rand = new Random();
    final ReferencedEnvelope bbox;
    final ResourceInfo resource;
    final StyleInfo style;
    final Component olPreview;

    public OpenLayersMapPanel(String id, LayerInfo layer, StyleInfo style) {
        super(id);
        this.resource = layer.getResource();
        this.bbox = resource.getLatLonBoundingBox();
        this.style = style;
        this.olPreview = new WebMarkupContainer("olPreview").setOutputMarkupId(true);
        add(olPreview);
        setOutputMarkupId(true);
        
        try {
            ensureLegendDecoration();
        } catch(IOException e) {
            LOGGER.log(Level.WARNING, "Failed to put legend layout file in the data directory, the legend decoration will not appear", e);
        }
    } 

    private void ensureLegendDecoration() throws IOException {
        GeoServerDataDirectory dd = GeoServerApplication.get().getBeanOfType(GeoServerDataDirectory.class);
        Resource layouts = dd.get("layouts");
        Resource legend = layouts.get("css-legend.xml");
        if(!Resources.exists(legend)) {
            String legendLayout = IOUtils.toString(OpenLayersMapPanel.class.getResourceAsStream("css-legend.xml"));
            OutputStream os = legend.out();
            try {
                IOUtils.write(legendLayout, os);
            } finally {
                os.close();
            }
        }
        
    }

    public void renderHead(IHeaderResponse header) {
        super.renderHead(header);
        try {
            renderHeaderCss(header);
            renderHeaderScript(header);
        } catch (IOException e) {
            throw new WicketRuntimeException(e);
        } catch (TemplateException e) {
            throw new WicketRuntimeException(e);
        }
    }

    private void renderHeaderCss(IHeaderResponse header) 
        throws IOException, TemplateException 
    {
        Map<String, Object> context = new HashMap<String, Object>();
        context.put("id", olPreview.getMarkupId());
        Template template = templates.getTemplate("ol-style.ftl");
        StringWriter css = new java.io.StringWriter();
        template.process(context, css);
        header.render(CssHeaderItem.forCSS(css.toString(),null));
    }

    private void renderHeaderScript(IHeaderResponse header) 
        throws IOException, TemplateException 
    {
        Map<String, Object> context = new HashMap<String, Object>();
        context.put("minx", bbox.getMinX());
        context.put("miny", bbox.getMinY());
        context.put("maxx", bbox.getMaxX());
        context.put("maxy", bbox.getMaxY());
        context.put("id", olPreview.getMarkupId());
        context.put("layer", resource.prefixedName());
        context.put("style", style.getName());
        if (style.getWorkspace() != null) {
          context.put("styleWorkspace", style.getWorkspace().getName());
        }
        context.put("cachebuster", rand.nextInt());
        context.put("resolution", Math.max(bbox.getSpan(0), bbox.getSpan(1)) / 256.0);
        HttpServletRequest req = GeoServerApplication.get().servletRequest();
        String base = ResponseUtils.baseURL(req);
        String baseUrl = ResponseUtils.buildURL(base, "/", null, URLType.RESOURCE);
        context.put("baseUrl", canonicUrl(baseUrl));
        Template template = templates.getTemplate("ol-load.ftl");
        StringWriter script = new java.io.StringWriter();
        template.process(context, script);
        header.render(JavaScriptHeaderItem.forUrl(ResponseUtils.buildURL(base, "/openlayers/OpenLayers.js", null, URLType.RESOURCE)));
        header.render(OnLoadHeaderItem.forScript(script.toString()));
    }
    
    /**
     * Makes sure the url does not end with "/", otherwise we would have URL lik
     * "http://localhost:8080/geoserver//wms?LAYERS=..." and Jetty 6.1 won't digest them...
     * 
     * @param baseUrl
     * @return
     */
    private String canonicUrl(String baseUrl) {
        if (baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1);
        } else {
            return baseUrl;
        }
    }

    public String getUpdateCommand() throws IOException, TemplateException {
        Map<String, Object> context = new HashMap<String, Object>();
        context.put("id", olPreview.getMarkupId());
        context.put("cachebuster", rand.nextInt());

        Template template = templates.getTemplate("ol-update.ftl");
        StringWriter script = new java.io.StringWriter();
        template.process(context, script);
        return script.toString();
    }
}
