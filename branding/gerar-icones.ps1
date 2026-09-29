# Gera todos os ícones do app a partir da logo. Uso (na raiz do projeto):
#   powershell -ExecutionPolicy Bypass -File branding/gerar-icones.ps1 -SrcPath branding/greencodes-logo.png -ResDir app/src/main/res
param([string]$SrcPath, [string]$ResDir)
Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @"
using System; using System.IO; using System.Drawing; using System.Drawing.Imaging; using System.Drawing.Drawing2D;
public static class Ico {
  // Remove o fundo branco: alfa vem da "distância do branco"; a cor é desmisturada do branco.
  static Bitmap Cutout(Bitmap src, bool silhouette) {
    int w = src.Width, h = src.Height; var dst = new Bitmap(w, h, PixelFormat.Format32bppArgb);
    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
      Color c = src.GetPixel(x, y);
      if (c.A == 0) { dst.SetPixel(x, y, Color.Transparent); continue; }
      int mn = Math.Min(c.R, Math.Min(c.G, c.B));
      // Respeita a transparência original e ainda remove eventual branco opaco.
      double a = (c.A / 255.0) * Math.Min(1.0, Math.Max(0.0, (255 - mn) / 160.0));
      if (a < 0.04) { dst.SetPixel(x, y, Color.Transparent); continue; }
      int A = (int)Math.Round(a * 255);
      if (silhouette) { dst.SetPixel(x, y, Color.FromArgb(A, 255, 255, 255)); continue; }
      double aw = a / (c.A / 255.0); // parte do alfa que veio do "branco"
      Func<int,int> un = v => Math.Max(0, Math.Min(255, (int)Math.Round((v - 255 * (1 - aw)) / aw)));
      dst.SetPixel(x, y, Color.FromArgb(A, un(c.R), un(c.G), un(c.B)));
    }
    return dst;
  }
  static Rectangle Bounds(Bitmap b) {
    int x0 = b.Width, y0 = b.Height, x1 = 0, y1 = 0;
    for (int y = 0; y < b.Height; y++) for (int x = 0; x < b.Width; x++)
      if (b.GetPixel(x, y).A > 20) { x0 = Math.Min(x0, x); y0 = Math.Min(y0, y); x1 = Math.Max(x1, x); y1 = Math.Max(y1, y); }
    return Rectangle.FromLTRB(x0, y0, x1 + 1, y1 + 1);
  }
  // Desenha o recorte centralizado num quadro size x size, ocupando 'fill' (0..1) do lado.
  static void Frame(Bitmap logo, Rectangle crop, int size, double fill, Color bg, bool circle, string path, bool whiten = false) {
    using (var b = new Bitmap(size, size, PixelFormat.Format32bppArgb)) {
      using (var g = Graphics.FromImage(b)) {
        g.SmoothingMode = SmoothingMode.AntiAlias; g.InterpolationMode = InterpolationMode.HighQualityBicubic;
        g.PixelOffsetMode = PixelOffsetMode.HighQuality; g.Clear(Color.Transparent);
        if (bg.A > 0) using (var br = new SolidBrush(bg)) {
          if (circle) g.FillEllipse(br, 0, 0, size - 1, size - 1);
          else { float m = size * 0.04f, r = size * 0.36f, e = size - 2 * m; using (var p = new GraphicsPath()) {
            p.AddArc(m, m, r, r, 180, 90); p.AddArc(m + e - r, m, r, r, 270, 90); p.AddArc(m + e - r, m + e - r, r, r, 0, 90); p.AddArc(m, m + e - r, r, r, 90, 90);
            p.CloseFigure(); g.FillPath(br, p); } }
        }
        double box = size * fill, s = box / Math.Max(crop.Width, crop.Height);
        int w = (int)Math.Round(crop.Width * s), h = (int)Math.Round(crop.Height * s);
        using (var ia = new ImageAttributes()) {
          ia.SetWrapMode(WrapMode.TileFlipXY);
          g.DrawImage(logo, new Rectangle((size - w) / 2, (size - h) / 2, w, h), crop.X, crop.Y, crop.Width, crop.Height, GraphicsUnit.Pixel, ia);
        }
      }
      if (whiten) for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) { var c = b.GetPixel(x, y); b.SetPixel(x, y, Color.FromArgb(c.A, 255, 255, 255)); }
      Directory.CreateDirectory(Path.GetDirectoryName(path));
      b.Save(path, ImageFormat.Png);
    }
  }
  public static string Run(string srcPath, string res) {
    using (var src = new Bitmap(srcPath))
    using (var color = Cutout(src, false))
    using (var mask = Cutout(src, true)) {
      var crop = Bounds(color);
      string[] dn = { "mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi" }; double[] ks = { 1, 1.5, 2, 3, 4 };
      for (int i = 0; i < dn.Length; i++) {
        double k = ks[i]; string m = Path.Combine(res, "mipmap-" + dn[i]), d = Path.Combine(res, "drawable-" + dn[i]);
        // Adaptativo (108dp): logo em ~52dp, dentro da zona segura circular de 66dp.
        Frame(color, crop, (int)(108 * k), 0.48, Color.Transparent, false, Path.Combine(m, "ic_launcher_foreground.png"));
        Frame(mask,  crop, (int)(108 * k), 0.48, Color.Transparent, false, Path.Combine(m, "ic_launcher_monochrome.png"), true);
        // Legado (48dp), Android 7.
        Frame(color, crop, (int)(48 * k), 0.66, Color.White, false, Path.Combine(m, "ic_launcher.png"));
        Frame(color, crop, (int)(48 * k), 0.62, Color.White, true,  Path.Combine(m, "ic_launcher_round.png"));
        // Ícone pequeno da barra de status (24dp): silhueta branca.
        Frame(mask, crop, (int)(24 * k), 0.92, Color.Transparent, false, Path.Combine(d, "ic_stat_greennotify.png"), true);
      }
      Frame(color, crop, 480, 1.0, Color.Transparent, false, Path.Combine(res, "drawable-nodpi", "greencodes_logo.png"));
      return "recorte da logo: " + crop;
    }
  }
}
"@
[Ico]::Run($SrcPath, $ResDir)
