package water.api;

import hex.ScoreTask;
import water.*;
import water.util.RString;
import water.util.Log;
import water.fvec.Frame;
import water.fvec.Vec;

/**
 * @author cliffc
 */
public class GeneratePredictions2 extends Request2 {
  static final int API_WEAVER = 1; // This file has auto-gen'd doc & json fields
  static public DocGen.FieldDoc[] DOC_FIELDS; // Initialized from Auto-Gen code.

  @API( help="Model", required=true, filter=Default.class )
  Model model;

  @API(help = "Data frame", required = true, filter = dataFilter.class)
  public Frame data;
  class dataFilter extends FrameKey { public dataFilter() { super("data"); } }

  @API( help="Prediction key", required=true, filter=Default.class )
  Key prediction_key;


  public static String link(Key k, String content) {
    RString rs = new RString("<a href='GeneratePredictions2.query?model=%$key'>%content</a>");
    rs.replace("key", k.toString());
    rs.replace("content", content);
    return rs.toString();
  }

  @Override protected Response serve() {
    try {
      if( model == null ) throw new IllegalArgumentException("Model is missing");
      Frame fr = model.score(data,true);
      UKV.put(prediction_key,fr);
      return Inspect2.redirect(this, prediction_key.toString());
    } catch (Throwable t) {
      Log.err(t);
      return Response.error(t.getMessage());
    }
  }
}
