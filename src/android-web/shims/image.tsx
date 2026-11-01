/* eslint-disable @next/next/no-img-element, jsx-a11y/alt-text */

import React from 'react';

type Props = Omit<React.ImgHTMLAttributes<HTMLImageElement>, 'src'> & {
  src: string | { src: string };
  fill?: boolean;
  priority?: boolean;
  unoptimized?: boolean;
};

export default function Image({
  src,
  fill,
  priority: _priority,
  unoptimized: _unoptimized,
  style,
  ...props
}: Props) {
  const rawSrc = typeof src === 'string' ? src : src.src;
  const resolvedSrc =
    rawSrc.startsWith('http://') || rawSrc.startsWith('https://')
      ? `/api/image-proxy?url=${encodeURIComponent(rawSrc)}`
      : rawSrc;
  return (
    <img
      {...props}
      src={resolvedSrc}
      style={
        fill
          ? {
              ...style,
              height: '100%',
              inset: 0,
              position: 'absolute',
              width: '100%',
            }
          : style
      }
    />
  );
}
