// SGP4/SDP4 orbit propagator (Vallado et al. 2006, "Revisiting Spacetrack Report #3"),
// WGS-72 constants, opsmode 'i'. JavaScript port of app/src/main/java/.../Sgp4.kt.
// epochDays: days since 1949-12-31 00:00 UT (JD − 2433281.5). Angles in radians,
// noKozai in rad/min. position(tsince minutes) returns TEME km as [x, y, z].
(function (root) {
  "use strict";
  const PI = Math.PI, TWO_PI = 2 * Math.PI, X2O3 = 2 / 3;
  const RE = 6378.135, MU = 398600.8;
  const XKE = 60.0 / Math.sqrt(RE * RE * RE / MU);
  const J2 = 0.001082616, J3 = -0.00000253881, J4 = -0.00000165597, J3OJ2 = J3 / J2;
  const { sin, cos, sqrt, abs, atan2, pow } = Math;
  const mod = (a, b) => ((a % b) + b) % b;   // Kotlin .mod / Python %

  function gstime(jdut1) {
    const tut1 = (jdut1 - 2451545.0) / 36525.0;
    const temp = -6.2e-6 * tut1 * tut1 * tut1 + 0.093104 * tut1 * tut1 +
      (876600.0 * 3600 + 8640184.812866) * tut1 + 67310.54841;
    return mod(temp * PI / 180 / 240.0, TWO_PI);
  }

  function Sgp4(epochDays, bstar, ecco0, argpo0, inclo0, mo0, noKozai, nodeo0) {
    let ecco = ecco0, argpo = argpo0, inclo = inclo0, mo = mo0, nodeo = nodeo0;
    let isimp = 0, deep = false;
    let aycof = 0, con41 = 0, cc1 = 0, cc4 = 0, cc5 = 0, d2 = 0, d3 = 0, d4 = 0, delmo = 0, eta = 0,
      argpdot = 0, omgcof = 0, sinmao = 0, t = 0, t2cof = 0, t3cof = 0, t4cof = 0, t5cof = 0,
      x1mth2 = 0, x7thm1 = 0, mdot = 0, nodedot = 0, xlcof = 0, xmcof = 0, nodecf = 0;
    let irez = 0, d2201 = 0, d2211 = 0, d3210 = 0, d3222 = 0, d4410 = 0, d4422 = 0, d5220 = 0,
      d5232 = 0, d5421 = 0, d5433 = 0, dedt = 0, del1 = 0, del2 = 0, del3 = 0, didt = 0, dmdt = 0,
      dnodt = 0, domdt = 0, e3 = 0, ee2 = 0, peo = 0, pgho = 0, pho = 0, pinco = 0, plo = 0,
      se2 = 0, se3 = 0, sgh2 = 0, sgh3 = 0, sgh4 = 0, sh2 = 0, sh3 = 0, si2 = 0, si3 = 0, sl2 = 0,
      sl3 = 0, sl4 = 0, gsto = 0, xfact = 0, xgh2 = 0, xgh3 = 0, xgh4 = 0, xh2 = 0, xh3 = 0,
      xi2 = 0, xi3 = 0, xl2 = 0, xl3 = 0, xl4 = 0, xlamo = 0, zmol = 0, zmos = 0, atime = 0,
      xli = 0, xni = 0;
    // dscom outputs consumed by dsinit
    let sinimC = 0, cosimC = 0, emsqC = 0, emC = 0, s1 = 0, s2 = 0, s3 = 0, s4 = 0, s5 = 0,
      ss1 = 0, ss2 = 0, ss3 = 0, ss4 = 0, ss5 = 0, sz1 = 0, sz3 = 0, sz11 = 0, sz13 = 0, sz21 = 0,
      sz23 = 0, sz31 = 0, sz33 = 0, z1 = 0, z3 = 0, z11 = 0, z13 = 0, z21 = 0, z23 = 0, z31 = 0,
      z33 = 0, nmC = 0;

    function dpper(inclo, init, ep, inclp, nodep, argpp, mp) {
      const zns = 1.19459e-5, zes = 0.01675, znl = 1.5835218e-4, zel = 0.05490;
      let zm = init ? zmos : zmos + zns * t;
      let zf = zm + 2.0 * zes * sin(zm);
      let sinzf = sin(zf);
      let f2 = 0.5 * sinzf * sinzf - 0.25;
      let f3 = -0.5 * sinzf * cos(zf);
      const ses = se2 * f2 + se3 * f3;
      const sis = si2 * f2 + si3 * f3;
      const sls = sl2 * f2 + sl3 * f3 + sl4 * sinzf;
      const sghs = sgh2 * f2 + sgh3 * f3 + sgh4 * sinzf;
      const shs = sh2 * f2 + sh3 * f3;
      zm = init ? zmol : zmol + znl * t;
      zf = zm + 2.0 * zel * sin(zm);
      sinzf = sin(zf);
      f2 = 0.5 * sinzf * sinzf - 0.25;
      f3 = -0.5 * sinzf * cos(zf);
      const sel = ee2 * f2 + e3 * f3;
      const sil = xi2 * f2 + xi3 * f3;
      const sll = xl2 * f2 + xl3 * f3 + xl4 * sinzf;
      const sghl = xgh2 * f2 + xgh3 * f3 + xgh4 * sinzf;
      const shll = xh2 * f2 + xh3 * f3;
      let pe = ses + sel, pinc = sis + sil, pl = sls + sll, pgh = sghs + sghl, ph = shs + shll;
      if (!init) {
        pe -= peo; pinc -= pinco; pl -= plo; pgh -= pgho; ph -= pho;
        inclp += pinc;
        ep += pe;
        const sinip = sin(inclp), cosip = cos(inclp);
        if (inclp >= 0.2) {
          ph /= sinip;
          pgh -= cosip * ph;
          argpp += pgh;
          nodep += ph;
          mp += pl;
        } else {
          const sinop = sin(nodep), cosop = cos(nodep);
          let alfdp = sinip * sinop, betdp = sinip * cosop;
          const dalf = ph * cosop + pinc * cosip * sinop;
          const dbet = -ph * sinop + pinc * cosip * cosop;
          alfdp += dalf; betdp += dbet;
          nodep %= TWO_PI;
          const xls = mp + argpp + pl + pgh + (cosip - pinc * sinip) * nodep;
          const xnoh = nodep;
          nodep = atan2(alfdp, betdp);
          if (abs(xnoh - nodep) > PI) nodep = nodep < xnoh ? nodep + TWO_PI : nodep - TWO_PI;
          mp += pl;
          argpp = xls - mp - cosip * nodep;
        }
      }
      return [ep, inclp, nodep, argpp, mp];
    }

    function dscom(epoch, ep, argpp, tc, inclp, nodep, np) {
      const zes = 0.01675, zel = 0.05490, c1ss = 2.9864797e-6, c1l = 4.7968065e-7;
      const zsinis = 0.39785416, zcosis = 0.91744867, zcosgs = 0.1945905, zsings = -0.98088458;
      nmC = np; emC = ep;
      const snodm = sin(nodep), cnodm = cos(nodep), sinomm = sin(argpp), cosomm = cos(argpp);
      sinimC = sin(inclp); cosimC = cos(inclp);
      emsqC = emC * emC;
      const betasq = 1.0 - emsqC, rtemsq = sqrt(betasq);
      peo = 0; pinco = 0; plo = 0; pgho = 0; pho = 0;
      const day = epoch + 18261.5 + tc / 1440.0;
      const xnodce = mod(4.5236020 - 9.2422029e-4 * day, TWO_PI);
      const stem = sin(xnodce), ctem = cos(xnodce);
      const zcosil = 0.91375164 - 0.03568096 * ctem;
      const zsinil = sqrt(1.0 - zcosil * zcosil);
      const zsinhl = 0.089683511 * stem / zsinil;
      const zcoshl = sqrt(1.0 - zsinhl * zsinhl);
      const gam = 5.8351514 + 0.0019443680 * day;
      let zx = 0.39785416 * stem / zsinil;
      const zy = zcoshl * ctem + 0.91744867 * zsinhl * stem;
      zx = atan2(zx, zy);
      zx = gam + zx - xnodce;
      const zcosgl = cos(zx), zsingl = sin(zx);
      let zcosg = zcosgs, zsing = zsings, zcosi = zcosis, zsini = zsinis, zcosh = cnodm, zsinh = snodm;
      let cc = c1ss;
      const xnoi = 1.0 / nmC;
      let ss6 = 0, ss7 = 0, sz2 = 0, sz12 = 0, sz22 = 0, sz32 = 0;
      let s6 = 0, s7 = 0, z2 = 0, z12 = 0, z22 = 0, z32 = 0;
      for (let lsflg = 1; lsflg <= 2; lsflg++) {
        const a1 = zcosg * zcosh + zsing * zcosi * zsinh;
        const a3 = -zsing * zcosh + zcosg * zcosi * zsinh;
        const a7 = -zcosg * zsinh + zsing * zcosi * zcosh;
        const a8 = zsing * zsini;
        const a9 = zsing * zsinh + zcosg * zcosi * zcosh;
        const a10 = zcosg * zsini;
        const a2 = cosimC * a7 + sinimC * a8;
        const a4 = cosimC * a9 + sinimC * a10;
        const a5 = -sinimC * a7 + cosimC * a8;
        const a6 = -sinimC * a9 + cosimC * a10;
        const x1 = a1 * cosomm + a2 * sinomm;
        const x2 = a3 * cosomm + a4 * sinomm;
        const x3 = -a1 * sinomm + a2 * cosomm;
        const x4 = -a3 * sinomm + a4 * cosomm;
        const x5 = a5 * sinomm, x6 = a6 * sinomm, x7 = a5 * cosomm, x8 = a6 * cosomm;
        z31 = 12.0 * x1 * x1 - 3.0 * x3 * x3;
        z32 = 24.0 * x1 * x2 - 6.0 * x3 * x4;
        z33 = 12.0 * x2 * x2 - 3.0 * x4 * x4;
        z1 = 3.0 * (a1 * a1 + a2 * a2) + z31 * emsqC;
        z2 = 6.0 * (a1 * a3 + a2 * a4) + z32 * emsqC;
        z3 = 3.0 * (a3 * a3 + a4 * a4) + z33 * emsqC;
        z11 = -6.0 * a1 * a5 + emsqC * (-24.0 * x1 * x7 - 6.0 * x3 * x5);
        z12 = -6.0 * (a1 * a6 + a3 * a5) + emsqC * (-24.0 * (x2 * x7 + x1 * x8) - 6.0 * (x3 * x6 + x4 * x5));
        z13 = -6.0 * a3 * a6 + emsqC * (-24.0 * x2 * x8 - 6.0 * x4 * x6);
        z21 = 6.0 * a2 * a5 + emsqC * (24.0 * x1 * x5 - 6.0 * x3 * x7);
        z22 = 6.0 * (a4 * a5 + a2 * a6) + emsqC * (24.0 * (x2 * x5 + x1 * x6) - 6.0 * (x4 * x7 + x3 * x8));
        z23 = 6.0 * a4 * a6 + emsqC * (24.0 * x2 * x6 - 6.0 * x4 * x8);
        z1 = z1 + z1 + betasq * z31;
        z2 = z2 + z2 + betasq * z32;
        z3 = z3 + z3 + betasq * z33;
        s3 = cc * xnoi;
        s2 = -0.5 * s3 / rtemsq;
        s4 = s3 * rtemsq;
        s1 = -15.0 * emC * s4;
        s5 = x1 * x3 + x2 * x4;
        s6 = x2 * x3 + x1 * x4;
        s7 = x2 * x4 - x1 * x3;
        if (lsflg === 1) {
          ss1 = s1; ss2 = s2; ss3 = s3; ss4 = s4; ss5 = s5; ss6 = s6; ss7 = s7;
          sz1 = z1; sz2 = z2; sz3 = z3; sz11 = z11; sz12 = z12; sz13 = z13;
          sz21 = z21; sz22 = z22; sz23 = z23; sz31 = z31; sz32 = z32; sz33 = z33;
          zcosg = zcosgl; zsing = zsingl; zcosi = zcosil; zsini = zsinil;
          zcosh = zcoshl * cnodm + zsinhl * snodm;
          zsinh = snodm * zcoshl - cnodm * zsinhl;
          cc = c1l;
        }
      }
      zmol = mod(4.7199672 + 0.22997150 * day - gam, TWO_PI);
      zmos = mod(6.2565837 + 0.017201977 * day, TWO_PI);
      se2 = 2.0 * ss1 * ss6; se3 = 2.0 * ss1 * ss7;
      si2 = 2.0 * ss2 * sz12; si3 = 2.0 * ss2 * (sz13 - sz11);
      sl2 = -2.0 * ss3 * sz2; sl3 = -2.0 * ss3 * (sz3 - sz1);
      sl4 = -2.0 * ss3 * (-21.0 - 9.0 * emsqC) * zes;
      sgh2 = 2.0 * ss4 * sz32; sgh3 = 2.0 * ss4 * (sz33 - sz31); sgh4 = -18.0 * ss4 * zes;
      sh2 = -2.0 * ss2 * sz22; sh3 = -2.0 * ss2 * (sz23 - sz21);
      ee2 = 2.0 * s1 * s6; e3 = 2.0 * s1 * s7;
      xi2 = 2.0 * s2 * z12; xi3 = 2.0 * s2 * (z13 - z11);
      xl2 = -2.0 * s3 * z2; xl3 = -2.0 * s3 * (z3 - z1);
      xl4 = -2.0 * s3 * (-21.0 - 9.0 * emsqC) * zel;
      xgh2 = 2.0 * s4 * z32; xgh3 = 2.0 * s4 * (z33 - z31); xgh4 = -18.0 * s4 * zel;
      xh2 = -2.0 * s2 * z22; xh3 = -2.0 * s2 * (z23 - z21);
    }

    function dsinit(cosim, emsq0, argpo, sinim, t, tc, gsto, mo, mdot, no, nodeo, nodedot,
      xpidot, ecco, eccsq, em0, inclm0, nm) {
      const q22 = 1.7891679e-6, q31 = 2.1460748e-6, q33 = 2.2123015e-7;
      const root22 = 1.7891679e-6, root44 = 7.3636953e-9, root54 = 2.1765803e-9;
      const rptim = 4.37526908801129966e-3, root32 = 3.7393792e-7, root52 = 1.1428639e-7;
      const znl = 1.5835218e-4, zns = 1.19459e-5;
      let em = em0, emsq = emsq0, inclm = inclm0;
      irez = 0;
      if (nm > 0.0034906585 && nm < 0.0052359877) irez = 1;
      if (nm >= 8.26e-3 && nm <= 9.24e-3 && em >= 0.5) irez = 2;
      const ses = ss1 * zns * ss5;
      const sis = ss2 * zns * (sz11 + sz13);
      const sls = -zns * ss3 * (sz1 + sz3 - 14.0 - 6.0 * emsq);
      const sghs = ss4 * zns * (sz31 + sz33 - 6.0);
      let shs = -zns * ss2 * (sz21 + sz23);
      if (inclm < 5.2359877e-2 || inclm > PI - 5.2359877e-2) shs = 0.0;
      if (sinim !== 0.0) shs /= sinim;
      const sgs = sghs - cosim * shs;
      dedt = ses + s1 * znl * s5;
      didt = sis + s2 * znl * (z11 + z13);
      dmdt = sls - znl * s3 * (z1 + z3 - 14.0 - 6.0 * emsq);
      const sghl = s4 * znl * (z31 + z33 - 6.0);
      let shll = -znl * s2 * (z21 + z23);
      if (inclm < 5.2359877e-2 || inclm > PI - 5.2359877e-2) shll = 0.0;
      domdt = sgs + sghl;
      dnodt = shs;
      if (sinim !== 0.0) {
        domdt -= cosim / sinim * shll;
        dnodt += shll / sinim;
      }
      const theta = mod(gsto + tc * rptim, TWO_PI);
      em += dedt * t;
      inclm += didt * t;
      if (irez !== 0) {
        const aonv = pow(nm / XKE, X2O3);
        if (irez === 2) {
          const cosisq = cosim * cosim;
          const emo = em;
          em = ecco;
          const emsqo = emsq;
          emsq = eccsq;
          const eoc = em * emsq;
          const g201 = -0.306 - (em - 0.64) * 0.440;
          let g211, g310, g322, g410, g422, g520, g533, g521, g532;
          if (em <= 0.65) {
            g211 = 3.616 - 13.2470 * em + 16.2900 * emsq;
            g310 = -19.302 + 117.3900 * em - 228.4190 * emsq + 156.5910 * eoc;
            g322 = -18.9068 + 109.7927 * em - 214.6334 * emsq + 146.5816 * eoc;
            g410 = -41.122 + 242.6940 * em - 471.0940 * emsq + 313.9530 * eoc;
            g422 = -146.407 + 841.8800 * em - 1629.014 * emsq + 1083.4350 * eoc;
            g520 = -532.114 + 3017.977 * em - 5740.032 * emsq + 3708.2760 * eoc;
          } else {
            g211 = -72.099 + 331.819 * em - 508.738 * emsq + 266.724 * eoc;
            g310 = -346.844 + 1582.851 * em - 2415.925 * emsq + 1246.113 * eoc;
            g322 = -342.585 + 1554.908 * em - 2366.899 * emsq + 1215.972 * eoc;
            g410 = -1052.797 + 4758.686 * em - 7193.992 * emsq + 3651.957 * eoc;
            g422 = -3581.690 + 16178.110 * em - 24462.770 * emsq + 12422.520 * eoc;
            g520 = em > 0.715 ? -5149.66 + 29936.92 * em - 54087.36 * emsq + 31324.56 * eoc
              : 1464.74 - 4664.75 * em + 3763.64 * emsq;
          }
          if (em < 0.7) {
            g533 = -919.22770 + 4988.6100 * em - 9064.7700 * emsq + 5542.21 * eoc;
            g521 = -822.71072 + 4568.6173 * em - 8491.4146 * emsq + 5337.524 * eoc;
            g532 = -853.66600 + 4690.2500 * em - 8624.7700 * emsq + 5341.4 * eoc;
          } else {
            g533 = -37995.780 + 161616.52 * em - 229838.20 * emsq + 109377.94 * eoc;
            g521 = -51752.104 + 218913.95 * em - 309468.16 * emsq + 146349.42 * eoc;
            g532 = -40023.880 + 170470.89 * em - 242699.48 * emsq + 115605.82 * eoc;
          }
          const sini2 = sinim * sinim;
          const f220 = 0.75 * (1.0 + 2.0 * cosim + cosisq);
          const f221 = 1.5 * sini2;
          const f321 = 1.875 * sinim * (1.0 - 2.0 * cosim - 3.0 * cosisq);
          const f322 = -1.875 * sinim * (1.0 + 2.0 * cosim - 3.0 * cosisq);
          const f441 = 35.0 * sini2 * f220;
          const f442 = 39.3750 * sini2 * sini2;
          const f522 = 9.84375 * sinim * (sini2 * (1.0 - 2.0 * cosim - 5.0 * cosisq) +
            0.33333333 * (-2.0 + 4.0 * cosim + 6.0 * cosisq));
          const f523 = sinim * (4.92187512 * sini2 * (-2.0 - 4.0 * cosim + 10.0 * cosisq) +
            6.56250012 * (1.0 + 2.0 * cosim - 3.0 * cosisq));
          const f542 = 29.53125 * sinim * (2.0 - 8.0 * cosim + cosisq * (-12.0 + 8.0 * cosim + 10.0 * cosisq));
          const f543 = 29.53125 * sinim * (-2.0 - 8.0 * cosim + cosisq * (12.0 + 8.0 * cosim - 10.0 * cosisq));
          const xno2 = nm * nm, ainv2 = aonv * aonv;
          let temp1 = 3.0 * xno2 * ainv2;
          let temp = temp1 * root22;
          d2201 = temp * f220 * g201;
          d2211 = temp * f221 * g211;
          temp1 *= aonv;
          temp = temp1 * root32;
          d3210 = temp * f321 * g310;
          d3222 = temp * f322 * g322;
          temp1 *= aonv;
          temp = 2.0 * temp1 * root44;
          d4410 = temp * f441 * g410;
          d4422 = temp * f442 * g422;
          temp1 *= aonv;
          temp = temp1 * root52;
          d5220 = temp * f522 * g520;
          d5232 = temp * f523 * g532;
          temp = 2.0 * temp1 * root54;
          d5421 = temp * f542 * g521;
          d5433 = temp * f543 * g533;
          xlamo = mod(mo + nodeo + nodeo - theta - theta, TWO_PI);
          xfact = mdot + dmdt + 2.0 * (nodedot + dnodt - rptim) - no;
          em = emo;
          emsq = emsqo;
        }
        if (irez === 1) {
          const g200 = 1.0 + emsq * (-2.5 + 0.8125 * emsq);
          const g310 = 1.0 + 2.0 * emsq;
          const g300 = 1.0 + emsq * (-6.0 + 6.60937 * emsq);
          const f220 = 0.75 * (1.0 + cosim) * (1.0 + cosim);
          const f311 = 0.9375 * sinim * sinim * (1.0 + 3.0 * cosim) - 0.75 * (1.0 + cosim);
          let f330 = 1.0 + cosim;
          f330 = 1.875 * f330 * f330 * f330;
          del1 = 3.0 * nm * nm * aonv * aonv;
          del2 = 2.0 * del1 * f220 * g200 * q22;
          del3 = 3.0 * del1 * f330 * g300 * q33 * aonv;
          del1 = del1 * f311 * g310 * q31 * aonv;
          xlamo = mod(mo + nodeo + argpo - theta, TWO_PI);
          xfact = mdot + xpidot - rptim + dmdt + domdt + dnodt - no;
        }
        xli = xlamo;
        xni = no;
        atime = 0.0;
      }
    }

    // ---- initl ----
    const ss = 78.0 / RE + 1.0;
    const qzms2t = pow((120.0 - 78.0) / RE, 4);
    const eccsq = ecco * ecco;
    const omeosq = 1.0 - eccsq;
    const rteosq = sqrt(omeosq);
    const cosio = cos(inclo);
    const cosio2 = cosio * cosio;
    const ak = pow(XKE / noKozai, X2O3);
    const d1 = 0.75 * J2 * (3.0 * cosio2 - 1.0) / (rteosq * omeosq);
    let del = d1 / (ak * ak);
    const adel = ak * (1.0 - del * del - del * (1.0 / 3.0 + 134.0 * del * del / 81.0));
    del = d1 / (adel * adel);
    const noUnkozai = noKozai / (1.0 + del);
    const ao = pow(XKE / noUnkozai, X2O3);
    const sinio = sin(inclo);
    const po = ao * omeosq;
    const con42 = 1.0 - 5.0 * cosio2;
    con41 = -con42 - cosio2 - cosio2;
    const posq = po * po;
    const rp = ao * (1.0 - ecco);
    gsto = gstime(epochDays + 2433281.5);
    const semiMajorAxisKm = pow(noUnkozai / XKE, -X2O3) * RE;

    // ---- sgp4init ----
    if (rp < 220.0 / RE + 1.0) isimp = 1;
    let sfour = ss, qzms24 = qzms2t;
    const perige = (rp - 1.0) * RE;
    if (perige < 156.0) {
      sfour = perige - 78.0;
      if (perige < 98.0) sfour = 20.0;
      qzms24 = pow((120.0 - sfour) / RE, 4);
      sfour = sfour / RE + 1.0;
    }
    const pinvsq = 1.0 / posq;
    const tsi = 1.0 / (ao - sfour);
    eta = ao * ecco * tsi;
    const etasq = eta * eta, eeta = ecco * eta;
    const psisq = abs(1.0 - etasq);
    const coef = qzms24 * pow(tsi, 4.0);
    const coef1 = coef / pow(psisq, 3.5);
    const cc2 = coef1 * noUnkozai * (ao * (1.0 + 1.5 * etasq + eeta * (4.0 + etasq)) +
      0.375 * J2 * tsi / psisq * con41 * (8.0 + 3.0 * etasq * (8.0 + etasq)));
    cc1 = bstar * cc2;
    let cc3 = 0.0;
    if (ecco > 1.0e-4) cc3 = -2.0 * coef * tsi * J3OJ2 * noUnkozai * sinio / ecco;
    x1mth2 = 1.0 - cosio2;
    cc4 = 2.0 * noUnkozai * coef1 * ao * omeosq * (eta * (2.0 + 0.5 * etasq) + ecco *
      (0.5 + 2.0 * etasq) - J2 * tsi / (ao * psisq) * (-3.0 * con41 * (1.0 - 2.0 * eeta + etasq *
      (1.5 - 0.5 * eeta)) + 0.75 * x1mth2 * (2.0 * etasq - eeta * (1.0 + etasq)) * cos(2.0 * argpo)));
    cc5 = 2.0 * coef1 * ao * omeosq * (1.0 + 2.75 * (etasq + eeta) + eeta * etasq);
    const cosio4 = cosio2 * cosio2;
    const temp1 = 1.5 * J2 * pinvsq * noUnkozai;
    const temp2 = 0.5 * temp1 * J2 * pinvsq;
    const temp3 = -0.46875 * J4 * pinvsq * pinvsq * noUnkozai;
    mdot = noUnkozai + 0.5 * temp1 * rteosq * con41 + 0.0625 * temp2 * rteosq *
      (13.0 - 78.0 * cosio2 + 137.0 * cosio4);
    argpdot = -0.5 * temp1 * con42 + 0.0625 * temp2 * (7.0 - 114.0 * cosio2 + 395.0 * cosio4) +
      temp3 * (3.0 - 36.0 * cosio2 + 49.0 * cosio4);
    const xhdot1 = -temp1 * cosio;
    nodedot = xhdot1 + (0.5 * temp2 * (4.0 - 19.0 * cosio2) + 2.0 * temp3 * (3.0 - 7.0 * cosio2)) * cosio;
    const xpidot = argpdot + nodedot;
    omgcof = bstar * cc3 * cos(argpo);
    xmcof = 0.0;
    if (ecco > 1.0e-4) xmcof = -X2O3 * coef * bstar / eeta;
    nodecf = 3.5 * omeosq * xhdot1 * cc1;
    t2cof = 1.5 * cc1;
    xlcof = abs(cosio + 1.0) > 1.5e-12 ? -0.25 * J3OJ2 * sinio * (3.0 + 5.0 * cosio) / (1.0 + cosio)
      : -0.25 * J3OJ2 * sinio * (3.0 + 5.0 * cosio) / 1.5e-12;
    aycof = -0.5 * J3OJ2 * sinio;
    const delmotemp = 1.0 + eta * cos(mo);
    delmo = delmotemp * delmotemp * delmotemp;
    sinmao = sin(mo);
    x7thm1 = 7.0 * cosio2 - 1.0;

    if (2 * PI / noUnkozai >= 225.0) {
      deep = true;
      isimp = 1;
      const tc = 0.0;
      const inclm = inclo;
      dscom(epochDays, ecco, argpo, tc, inclo, nodeo, noUnkozai);
      const p = dpper(inclm, true, ecco, inclo, nodeo, argpo, mo);
      ecco = p[0]; inclo = p[1]; nodeo = p[2]; argpo = p[3]; mo = p[4];
      dsinit(cosimC, emsqC, argpo, sinimC, t, tc, gsto, mo, mdot, noUnkozai, nodeo,
        nodedot, xpidot, ecco, eccsq, emC, inclm, nmC);
    }
    if (isimp !== 1) {
      const cc1sq = cc1 * cc1;
      d2 = 4.0 * ao * tsi * cc1sq;
      const temp = d2 * tsi * cc1 / 3.0;
      d3 = (17.0 * ao + sfour) * temp;
      d4 = 0.5 * temp * ao * tsi * (221.0 * ao + 31.0 * sfour) * cc1;
      t3cof = d2 + 2.0 * cc1sq;
      t4cof = 0.25 * (3.0 * d3 + cc1 * (12.0 * d2 + 10.0 * cc1sq));
      t5cof = 0.2 * (3.0 * d4 + 12.0 * cc1 * d3 + 6.0 * d2 * d2 + 15.0 * cc1sq * (2.0 * d2 + cc1sq));
    }

    function position(tsince) {
      t = tsince;
      const xmdf = mo + mdot * t;
      const argpdf = argpo + argpdot * t;
      const nodedf = nodeo + nodedot * t;
      let argpm = argpdf, mm = xmdf;
      const t2 = t * t;
      let nodem = nodedf + nodecf * t2;
      let tempa = 1.0 - cc1 * t;
      let tempe = bstar * cc4 * t;
      let templ = t2cof * t2;
      if (isimp !== 1) {
        const delomg = omgcof * t;
        const delmtemp = 1.0 + eta * cos(xmdf);
        const delm = xmcof * (delmtemp * delmtemp * delmtemp - delmo);
        const temp = delomg + delm;
        mm = xmdf + temp;
        argpm = argpdf - temp;
        const t3 = t2 * t, t4 = t3 * t;
        tempa = tempa - d2 * t2 - d3 * t3 - d4 * t4;
        tempe += bstar * cc5 * (sin(mm) - sinmao);
        templ = templ + t3cof * t3 + t4 * (t4cof + t * t5cof);
      }
      let nm = noUnkozai, em = ecco, inclm = inclo;
      if (deep) {
        const fasx2 = 0.13130908, fasx4 = 2.8843198, fasx6 = 0.37448087;
        const g22 = 5.7686396, g32 = 0.95240898, g44 = 1.8014998, g52 = 1.0508330, g54 = 4.4108898;
        const rptim = 4.37526908801129966e-3, stepp = 720.0, stepn = -720.0, step2 = 259200.0;
        const theta = mod(gsto + t * rptim, TWO_PI);
        em += dedt * t;
        inclm += didt * t;
        argpm += domdt * t;
        nodem += dnodt * t;
        mm += dmdt * t;
        let ft = 0.0;
        if (irez !== 0) {
          if (atime === 0.0 || t * atime <= 0.0 || abs(t) < abs(atime)) {
            atime = 0.0; xni = noUnkozai; xli = xlamo;
          }
          const delt = t > 0.0 ? stepp : stepn;
          let xndt = 0, xldot = 0, xnddt = 0;
          for (;;) {
            if (irez !== 2) {
              xndt = del1 * sin(xli - fasx2) + del2 * sin(2.0 * (xli - fasx4)) + del3 * sin(3.0 * (xli - fasx6));
              xldot = xni + xfact;
              xnddt = del1 * cos(xli - fasx2) + 2.0 * del2 * cos(2.0 * (xli - fasx4)) +
                3.0 * del3 * cos(3.0 * (xli - fasx6));
              xnddt *= xldot;
            } else {
              const xomi = argpo + argpdot * atime;
              const x2omi = xomi + xomi, x2li = xli + xli;
              xndt = d2201 * sin(x2omi + xli - g22) + d2211 * sin(xli - g22) +
                d3210 * sin(xomi + xli - g32) + d3222 * sin(-xomi + xli - g32) +
                d4410 * sin(x2omi + x2li - g44) + d4422 * sin(x2li - g44) +
                d5220 * sin(xomi + xli - g52) + d5232 * sin(-xomi + xli - g52) +
                d5421 * sin(xomi + x2li - g54) + d5433 * sin(-xomi + x2li - g54);
              xldot = xni + xfact;
              xnddt = d2201 * cos(x2omi + xli - g22) + d2211 * cos(xli - g22) +
                d3210 * cos(xomi + xli - g32) + d3222 * cos(-xomi + xli - g32) +
                d5220 * cos(xomi + xli - g52) + d5232 * cos(-xomi + xli - g52) +
                2.0 * (d4410 * cos(x2omi + x2li - g44) + d4422 * cos(x2li - g44) +
                  d5421 * cos(xomi + x2li - g54) + d5433 * cos(-xomi + x2li - g54));
              xnddt *= xldot;
            }
            if (abs(t - atime) >= stepp) {
              xli += xldot * delt + xndt * step2;
              xni += xndt * delt + xnddt * step2;
              atime += delt;
            } else {
              ft = t - atime;
              break;
            }
          }
          nm = xni + xndt * ft + xnddt * ft * ft * 0.5;
          const xl = xli + xldot * ft + xndt * ft * ft * 0.5;
          mm = irez !== 1 ? xl - 2.0 * nodem + 2.0 * theta : xl - nodem - argpm + theta;
        }
      }
      const am = pow(XKE / nm, X2O3) * tempa * tempa;
      nm = XKE / pow(am, 1.5);
      em -= tempe;
      if (em < 1.0e-6) em = 1.0e-6;
      mm += noUnkozai * templ;
      let xlm = mm + argpm + nodem;
      nodem %= TWO_PI;
      argpm = mod(argpm, TWO_PI);
      xlm = mod(xlm, TWO_PI);
      mm = mod(xlm - argpm - nodem, TWO_PI);

      const sinim = sin(inclm), cosim = cos(inclm);
      let ep = em, xincp = inclm, argpp = argpm, nodep = nodem, mp = mm, sinip = sinim, cosip = cosim;
      if (deep) {
        const p = dpper(inclo, false, ep, xincp, nodep, argpp, mp);
        ep = p[0]; xincp = p[1]; nodep = p[2]; argpp = p[3]; mp = p[4];
        if (xincp < 0.0) { xincp = -xincp; nodep += PI; argpp -= PI; }
        sinip = sin(xincp); cosip = cos(xincp);
        aycof = -0.5 * J3OJ2 * sinip;
        xlcof = abs(cosip + 1.0) > 1.5e-12 ? -0.25 * J3OJ2 * sinip * (3.0 + 5.0 * cosip) / (1.0 + cosip)
          : -0.25 * J3OJ2 * sinip * (3.0 + 5.0 * cosip) / 1.5e-12;
      }
      const axnl = ep * cos(argpp);
      let temp = 1.0 / (am * (1.0 - ep * ep));
      const aynl = ep * sin(argpp) + temp * aycof;
      const xl = mp + argpp + nodep + temp * xlcof * axnl;
      const u = mod(xl - nodep, TWO_PI);
      let eo1 = u, tem5 = 9999.9, ktr = 1, sineo1 = 0, coseo1 = 0;
      while (abs(tem5) >= 1.0e-12 && ktr <= 10) {
        sineo1 = sin(eo1); coseo1 = cos(eo1);
        tem5 = 1.0 - coseo1 * axnl - sineo1 * aynl;
        tem5 = (u - aynl * coseo1 + axnl * sineo1 - eo1) / tem5;
        if (abs(tem5) >= 0.95) tem5 = tem5 > 0.0 ? 0.95 : -0.95;
        eo1 += tem5;
        ktr++;
      }
      const ecose = axnl * coseo1 + aynl * sineo1;
      const esine = axnl * sineo1 - aynl * coseo1;
      const el2 = axnl * axnl + aynl * aynl;
      const pl = am * (1.0 - el2);
      const rl = am * (1.0 - ecose);
      const betal = sqrt(1.0 - el2);
      temp = esine / (1.0 + betal);
      const sinu = am / rl * (sineo1 - aynl - axnl * temp);
      const cosu = am / rl * (coseo1 - axnl + aynl * temp);
      let su = atan2(sinu, cosu);
      const sin2u = (cosu + cosu) * sinu;
      const cos2u = 1.0 - 2.0 * sinu * sinu;
      temp = 1.0 / pl;
      const tmp1 = 0.5 * J2 * temp;
      const tmp2 = tmp1 * temp;
      if (deep) {
        const cosisq = cosip * cosip;
        con41 = 3.0 * cosisq - 1.0;
        x1mth2 = 1.0 - cosisq;
        x7thm1 = 7.0 * cosisq - 1.0;
      }
      const mrt = rl * (1.0 - 1.5 * tmp2 * betal * con41) + 0.5 * tmp1 * x1mth2 * cos2u;
      su -= 0.25 * tmp2 * x7thm1 * sin2u;
      const xnode = nodep + 1.5 * tmp2 * cosip * sin2u;
      const xinc = xincp + 1.5 * tmp2 * cosip * sinip * cos2u;
      const sinsu = sin(su), cossu = cos(su), snod = sin(xnode), cnod = cos(xnode);
      const sini = sin(xinc), cosi = cos(xinc);
      const xmx = -snod * cosi, xmy = cnod * cosi;
      const ux = xmx * sinsu + cnod * cossu;
      const uy = xmy * sinsu + snod * cossu;
      const uz = sini * sinsu;
      const mr = mrt * RE;
      return [mr * ux, mr * uy, mr * uz];
    }

    return { position, noUnkozai, semiMajorAxisKm };
  }

  const api = { Sgp4, gstime };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.SGP4 = api;
})(typeof self !== "undefined" ? self : this);
