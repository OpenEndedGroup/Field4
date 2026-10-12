#!/usr/bin/env python3
"""Plot the production-Kotlin CSVs from GaussianProcessFiguresTest; no GP maths lives here.

FIELD_GP_FIGURE_DATA="$PWD/build/gp-figure-data" ./gradlew jvmTest --tests afield.stdlib.GaussianProcessFiguresTest --rerun-tasks
python tools/gp/render_figures.py build/gp-figure-data public/docs/assets/gaussian_processes
Requires numpy and matplotlib. These are numerical figures, not captures of the Field UI.
"""
import argparse
from pathlib import Path
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.patches import Ellipse


def render(source: Path, target: Path):
    target.mkdir(parents=True, exist_ok=True)
    plt.rcParams.update({'font.family': 'DejaVu Sans', 'font.size': 11, 'axes.spines.top': False,
                         'axes.spines.right': False, 'figure.facecolor': '#faf9f6', 'axes.facecolor': '#faf9f6',
                         'axes.labelcolor': '#363c42', 'text.color': '#27313a', 'axes.titleweight': 'bold'})
    colors = ['#186b83', '#a14249', '#88752c', '#756194', '#4a805f']
    def read(name): return np.genfromtxt(source / f'{name}.csv', delimiter=',', names=True)
    def save(fig, name):
        fig.savefig(target / f'{name}.png', dpi=160, bbox_inches='tight', facecolor=fig.get_facecolor())
        plt.close(fig)
    d, obs = read('conditioning'), read('observations')
    fig, axes = plt.subplots(2, 1, figsize=(9, 6.4), sharex=True, constrained_layout=True)
    for i, ax in enumerate(axes):
        mu = np.zeros(len(d)) if i == 0 else d['mean']
        std = np.ones(len(d)) if i == 0 else d['std']
        ax.fill_between(d['x'], mu - 1.96*std, mu+1.96*std, color='#d9e4e6', label='Pointwise 95% interval')
        for j, color in enumerate(colors): ax.plot(d['x'], d[f'prior{j}' if i == 0 else f'sample{j}'], color=color, lw=1.2, alpha=0.85)
        ax.plot(d['x'], mu, color='#182a31', lw=2.4, label='Mean')
        if i: ax.errorbar(obs['x'], obs['y'], yerr=1.96*obs['noise'], fmt='o', color='#172c34', ms=5, capsize=3)
        ax.set_title('Before observations' if i == 0 else 'After observations: five whole-function draws', loc='left')
        ax.set_ylabel('f(x)'); ax.set_ylim(-2.8, 2.8); ax.grid(alpha=0.15)
    axes[-1].set_xlabel('x'); axes[0].legend(loc='upper right', fontsize=9)
    save(fig, 'conditioning')
    d=read('kernels'); names=['RBF · infinitely smooth', 'Matérn 5/2 · twice differentiable', 'Matérn 3/2 · once differentiable', 'Matérn 1/2 · rough', 'Periodic · repeats exactly']
    fig, axes=plt.subplots(5, 1, figsize=(9, 9), sharex=True, constrained_layout=True)
    for i,ax in enumerate(axes):
        r=d[d['kernel']==i]
        for j,color in enumerate(colors[:3]):ax.plot(r['x'],r[f'sample{j}'],color=color,lw=1.25)
        ax.set_title(names[i],loc='left',fontsize=11);ax.set_ylim(-4,4);ax.grid(alpha=0.15)
    axes[-1].set_xlabel('x');save(fig,'kernels')
    d=read('field2d');obs=read('observations2d');side=len(np.unique(d['x']))
    fig,axes=plt.subplots(2,2,figsize=(9,7.3),constrained_layout=True)
    limit=max(np.max(np.abs(d['sample0'])),np.max(np.abs(d['sample1'])),np.max(np.abs(d['mean'])))
    for ax,col,title in zip(axes.flat,['mean','std','sample0','sample1'],['Posterior mean','Posterior standard deviation','One joint sample','Another joint sample']):
        isstd=col=='std';im=ax.imshow(d[col].reshape(side,side),origin='lower',extent=(-1.2,1.2,-1.2,1.2),cmap='viridis' if isstd else 'RdBu_r',vmin=0 if isstd else -limit,vmax=1 if isstd else limit,interpolation='nearest')
        ax.scatter(obs['x'],obs['y'],s=28,facecolors='none',edgecolors='black',linewidths=1)
        ax.set_title(title,loc='left',fontsize=11);ax.set_xlabel('x');ax.set_ylabel('y');fig.colorbar(im,ax=ax,shrink=0.8)
    save(fig,'field2d')
    d=read('field3d');fig,axes=plt.subplots(2,3,figsize=(10,6.5),constrained_layout=True)
    limit=np.max(np.abs(d['sample']))
    for col,z in enumerate(np.unique(d['z'])):
        r=d[d['z']==z]
        for row,key in enumerate(['sample','std']):
            ax=axes[row,col];isstd=row==1
            im=ax.imshow(r[key].reshape(25,25),origin='lower',extent=(-1.2,1.2,-1.2,1.2),cmap='viridis' if isstd else 'RdBu_r',vmin=0 if isstd else -limit,vmax=1 if isstd else limit)
            ax.set_title(('Standard deviation' if isstd else 'Same sampled volume')+f' · z={z:g}',fontsize=10,loc='left');ax.set_xlabel('x')
            if col==0:ax.set_ylabel('y')
        fig.colorbar(im,ax=axes[1,col],shrink=0.65)
    fig.colorbar(axes[0,0].images[0],ax=axes[0,:],shrink=0.7,label='f(x,y,z)')
    save(fig,'field3d')
    d=read('curve');obs=read('curve_observations');fig,axes=plt.subplots(1,2,figsize=(10,4.8),constrained_layout=True)
    for j,color in enumerate(colors):axes[0].plot(d[f's{j}x'],d[f's{j}y'],color=color,lw=1.2,alpha=0.8)
    for ax in axes:
        ax.plot(d['x'],d['y'],color='#182a31',lw=2.5)
        ax.scatter(obs['x'],obs['y'],c='#172c34',s=24,zorder=5)
        ax.set_aspect('equal');ax.grid(alpha=0.15);ax.set_xlabel('x');ax.set_ylabel('y');ax.set_xlim(-1.5,1.5);ax.set_ylim(-1.3,1.95)
    for i in range(0,len(d),8):
        c=np.array([[d['varx'][i],d['covxy'][i]],[d['covxy'][i],d['vary'][i]]])
        eigen,v=np.linalg.eigh(c);eigen=np.maximum(0,eigen);angle=np.degrees(np.arctan2(v[1,1],v[0,1]))
        # 95% bivariate Gaussian ellipse: chi-square(df=2, p=.95) = -2 log(.05).
        radii=np.sqrt(-2*np.log(.05)*eigen)
        axes[1].add_patch(Ellipse((d['x'][i],d['y'][i]),2*radii[1],2*radii[0],angle=angle,facecolor='#90b6bf',edgecolor='#447f8e',alpha=0.3,lw=0.8))
    axes[0].set_title('Five coherent curve draws',loc='left');axes[1].set_title('Pointwise 95% position ellipses',loc='left')
    save(fig,'curve')

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source',type=Path);parser.add_argument('target',type=Path)
    args=parser.parse_args();render(args.source,args.target)
